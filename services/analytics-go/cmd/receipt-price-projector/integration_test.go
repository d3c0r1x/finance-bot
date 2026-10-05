//go:build integration

package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/prices"
	"github.com/segmentio/kafka-go"
)

const integrationReceiptEvent = `{"event_id":"00000000-0000-4000-8000-000000000001","event_type":"receipt.confirmed","schema_version":1,"tenant_id":"00000000-0000-4000-8000-000000000002","aggregate_type":"receipt","aggregate_id":"00000000-0000-4000-8000-000000000003","aggregate_version":2,"occurred_at":"2026-10-01T09:00:00Z","recorded_at":"2026-10-01T09:00:01Z","producer":"core","correlation_id":"00000000-0000-4000-8000-000000000008","payload":{"owner_user_id":"00000000-0000-4000-8000-000000000004","transaction_id":"00000000-0000-4000-8000-000000000005","receipt_date":"2026-10-01","currency":"RUB","merchant":"Market","items":[{"item_id":"00000000-0000-4000-8000-000000000006","name":"Tea 500g","quantity":"2.000000","line_sum":"50.00"}]}}`

func TestReceiptPriceProjectorKafkaClickHouseIntegration(t *testing.T) {
	brokers := strings.Split(os.Getenv("FINANCE_PRICE_IT_BROKERS"), ",")
	clickHouseURL := os.Getenv("FINANCE_PRICE_IT_CLICKHOUSE_URL")
	clickHouseUser := os.Getenv("FINANCE_PRICE_IT_CLICKHOUSE_USER")
	clickHousePassword := os.Getenv("FINANCE_PRICE_IT_CLICKHOUSE_PASSWORD")
	if len(brokers) == 0 || strings.TrimSpace(brokers[0]) == "" || clickHouseURL == "" ||
		clickHouseUser == "" || clickHousePassword == "" {
		t.Skip("Kafka and ClickHouse integration endpoints are not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	if err := executeClickHouse(ctx, clickHouseURL, "default", clickHouseUser, clickHousePassword,
		"CREATE DATABASE IF NOT EXISTS finance_analytics"); err != nil {
		t.Fatalf("create integration database: %v", err)
	}
	_, source, _, _ := runtime.Caller(0)
	ddlPath := filepath.Join(filepath.Dir(source), "..", "..", "storage", "clickhouse", "001_receipt_price_items.sql")
	ddl, err := os.ReadFile(ddlPath)
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range strings.Split(string(ddl), ";") {
		statement = strings.TrimSpace(statement)
		if statement == "" || strings.HasPrefix(statement, "CREATE DATABASE") {
			continue
		}
		if err := executeClickHouse(ctx, clickHouseURL, "finance_analytics", clickHouseUser, clickHousePassword,
			statement); err != nil {
			t.Fatalf("apply receipt price DDL: %v", err)
		}
	}
	store, err := prices.NewClickHouseHTTPStore(prices.ClickHouseHTTPConfig{
		Endpoint: clickHouseURL, Database: "finance_analytics", Username: clickHouseUser, Password: clickHousePassword,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Ping(ctx); err != nil {
		t.Fatalf("ClickHouse readiness: %v", err)
	}

	suffix := integrationUUID()
	sourceTopic := "finance.receipts.integration." + suffix
	dlqTopic := sourceTopic + ".dlq"
	groupID := "analytics-price-integration-" + suffix
	dialer := &kafka.Dialer{Timeout: 10 * time.Second, DualStack: true}
	connection, err := dialer.DialContext(ctx, "tcp", brokers[0])
	if err != nil {
		t.Fatalf("connect to Kafka: %v", err)
	}
	defer connection.Close()
	if err := connection.CreateTopics(
		kafka.TopicConfig{Topic: sourceTopic, NumPartitions: 1, ReplicationFactor: 1},
		kafka.TopicConfig{Topic: dlqTopic, NumPartitions: 1, ReplicationFactor: 1},
	); err != nil {
		t.Fatalf("create integration topics: %v", err)
	}

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: brokers, Topic: sourceTopic, GroupID: groupID, Dialer: dialer,
		MinBytes: 1, MaxBytes: 1 << 20, MaxWait: time.Second, CommitInterval: 0,
		IsolationLevel: kafka.ReadCommitted, StartOffset: kafka.FirstOffset,
	})
	defer reader.Close()
	dlqWriter := &kafka.Writer{
		Addr: kafka.TCP(brokers...), Topic: dlqTopic, Balancer: &kafka.Hash{},
		MaxAttempts: 5, RequiredAcks: kafka.RequireAll, Async: false, AllowAutoTopicCreation: false,
	}
	defer dlqWriter.Close()
	broker := &kafkaReceiptPriceBroker{reader: reader, deadLetter: dlqWriter}
	worker, err := prices.NewReceiptPriceKafkaWorker(broker, store, prices.ProjectionRetryPolicy{MaxAttempts: 1})
	if err != nil {
		t.Fatal(err)
	}

	eventID, tenantID, ownerID, receiptID, itemID := integrationUUID(), integrationUUID(), integrationUUID(), integrationUUID(), integrationUUID()
	transactionID := integrationUUID()
	event := strings.NewReplacer(
		"00000000-0000-4000-8000-000000000001", eventID,
		"00000000-0000-4000-8000-000000000002", tenantID,
		"00000000-0000-4000-8000-000000000003", receiptID,
		"00000000-0000-4000-8000-000000000004", ownerID,
		"00000000-0000-4000-8000-000000000005", transactionID,
		"00000000-0000-4000-8000-000000000006", itemID,
	).Replace(integrationReceiptEvent)
	producer := &kafka.Writer{
		Addr: kafka.TCP(brokers...), Topic: sourceTopic, Balancer: &kafka.Hash{},
		MaxAttempts: 5, RequiredAcks: kafka.RequireAll, Async: false, AllowAutoTopicCreation: false,
	}
	defer producer.Close()
	for range 2 {
		if err := producer.WriteMessages(ctx, kafka.Message{Key: []byte("receipt:" + receiptID), Value: []byte(event)}); err != nil {
			t.Fatalf("publish confirmed receipt event: %v", err)
		}
	}
	for range 2 {
		message, err := reader.FetchMessage(ctx)
		if err != nil {
			t.Fatalf("fetch confirmed receipt event: %v", err)
		}
		if err := worker.Process(ctx, receiptKafkaMessage(message)); err != nil {
			t.Fatalf("project and commit confirmed receipt event: %v", err)
		}
	}
	history, err := store.History(ctx, tenantID, ownerID, time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC), 100)
	if err != nil {
		t.Fatalf("read deduplicated ClickHouse history: %v", err)
	}
	if len(history) != 1 || history[0].ReceiptID != receiptID || history[0].ItemID != itemID || history[0].UnitPrice != "25.000000" {
		t.Fatalf("ClickHouse history = %+v, want one deduplicated 25.000000 price point", history)
	}

	dlqReader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: brokers, Topic: dlqTopic, GroupID: groupID + "-dlq", Dialer: dialer,
		MinBytes: 1, MaxBytes: 1 << 20, MaxWait: time.Second, CommitInterval: 0,
		IsolationLevel: kafka.ReadCommitted, StartOffset: kafka.FirstOffset,
	})
	defer dlqReader.Close()
	invalidEvent := strings.Replace(event, `"receipt.confirmed"`, `"receipt.changed"`, 1)
	if err := producer.WriteMessages(ctx, kafka.Message{Key: []byte("receipt:" + receiptID), Value: []byte(invalidEvent)}); err != nil {
		t.Fatalf("publish invalid receipt event: %v", err)
	}
	invalid, err := reader.FetchMessage(ctx)
	if err != nil {
		t.Fatalf("fetch invalid receipt event: %v", err)
	}
	if err := worker.Process(ctx, receiptKafkaMessage(invalid)); err != nil {
		t.Fatalf("dead-letter and commit invalid event: %v", err)
	}
	deadLetter, err := dlqReader.FetchMessage(ctx)
	if err != nil {
		t.Fatalf("read invalid-event dead letter: %v", err)
	}
	var safeRecord map[string]any
	if err := json.Unmarshal(deadLetter.Value, &safeRecord); err != nil {
		t.Fatalf("decode dead-letter metadata: %v", err)
	}
	if safeRecord["event_id"] != eventID || safeRecord["error_code"] != "invalid_receipt_confirmed_event" ||
		safeRecord["origin_topic"] != sourceTopic || strings.Contains(string(deadLetter.Value), ownerID) ||
		strings.Contains(string(deadLetter.Value), "Tea 500g") {
		t.Fatalf("dead-letter payload is not safe source metadata: %s", deadLetter.Value)
	}
}

func receiptKafkaMessage(message kafka.Message) prices.ReceiptPriceMessage {
	return prices.ReceiptPriceMessage{
		Topic: message.Topic, Partition: message.Partition, Offset: message.Offset, Key: message.Key, Value: message.Value,
	}
}

func integrationUUID() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		panic(err)
	}
	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80
	encoded := hex.EncodeToString(value)
	return fmt.Sprintf("%s-%s-%s-%s-%s", encoded[:8], encoded[8:12], encoded[12:16], encoded[16:20], encoded[20:])
}

func executeClickHouse(ctx context.Context, endpoint, database, username, password, query string) error {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return err
	}
	parameters := parsed.Query()
	parameters.Set("database", database)
	parameters.Set("wait_end_of_query", "1")
	parsed.RawQuery = parameters.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, parsed.String(), bytes.NewBufferString(query))
	if err != nil {
		return err
	}
	request.SetBasicAuth(username, password)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 4097))
	if err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("ClickHouse returned HTTP %d: %s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}
