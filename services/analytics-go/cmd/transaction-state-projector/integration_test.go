//go:build integration

package main

import (
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
	"github.com/d3c0r1x/finance-bot/services/analytics-go/recurring"
	"github.com/segmentio/kafka-go"
)

func TestTransactionStateProjectorKafkaClickHouseIntegration(t *testing.T) {
	brokers := splitBrokers(os.Getenv("FINANCE_RECURRING_IT_BROKERS"))
	clickHouseURL := os.Getenv("FINANCE_RECURRING_IT_CLICKHOUSE_URL")
	clickHouseUser := os.Getenv("FINANCE_RECURRING_IT_CLICKHOUSE_USER")
	clickHousePassword := os.Getenv("FINANCE_RECURRING_IT_CLICKHOUSE_PASSWORD")
	if len(brokers) == 0 || clickHouseURL == "" || clickHouseUser == "" || clickHousePassword == "" {
		t.Skip("Kafka and ClickHouse integration endpoints are not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	if err := executeRecurringClickHouse(ctx, clickHouseURL, "default", clickHouseUser, clickHousePassword,
		"CREATE DATABASE IF NOT EXISTS finance_analytics"); err != nil {
		t.Fatalf("create integration database: %v", err)
	}
	_, source, _, _ := runtime.Caller(0)
	ddlPath := filepath.Join(filepath.Dir(source), "..", "..", "storage", "clickhouse", "002_transaction_states.sql")
	ddl, err := os.ReadFile(ddlPath)
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range strings.Split(string(ddl), ";") {
		statement = strings.TrimSpace(statement)
		if statement == "" {
			continue
		}
		if err := executeRecurringClickHouse(ctx, clickHouseURL, "finance_analytics", clickHouseUser, clickHousePassword,
			statement); err != nil {
			t.Fatalf("apply recurring ClickHouse DDL: %v", err)
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

	suffix := recurringIntegrationUUID()
	sourceTopic, deadLetterTopic := "finance.transactions.integration."+suffix, "finance.transactions.integration."+suffix+".dlq"
	groupID := "analytics-recurring-integration-" + suffix
	dialer := &kafka.Dialer{Timeout: 10 * time.Second, DualStack: true}
	connection, err := dialer.DialContext(ctx, "tcp", brokers[0])
	if err != nil {
		t.Fatalf("connect to Kafka: %v", err)
	}
	defer connection.Close()
	if err := connection.CreateTopics(
		kafka.TopicConfig{Topic: sourceTopic, NumPartitions: 1, ReplicationFactor: 1},
		kafka.TopicConfig{Topic: deadLetterTopic, NumPartitions: 1, ReplicationFactor: 1},
	); err != nil {
		t.Fatalf("create integration topics: %v", err)
	}
	defer connection.DeleteTopics(sourceTopic, deadLetterTopic)
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: brokers, Topic: sourceTopic, GroupID: groupID, Dialer: dialer,
		MinBytes: 1, MaxBytes: 1 << 20, MaxWait: time.Second, CommitInterval: 0,
		IsolationLevel: kafka.ReadCommitted, StartOffset: kafka.FirstOffset,
	})
	defer reader.Close()
	deadLetterWriter := &kafka.Writer{
		Addr: kafka.TCP(brokers...), Topic: deadLetterTopic, Balancer: &kafka.Hash{},
		MaxAttempts: 5, RequiredAcks: kafka.RequireAll, Async: false, AllowAutoTopicCreation: false,
	}
	defer deadLetterWriter.Close()
	worker, err := recurring.NewKafkaWorker(&kafkaRecurringBroker{reader: reader, deadLetter: deadLetterWriter}, store,
		recurring.RetryPolicy{MaxAttempts: 2})
	if err != nil {
		t.Fatal(err)
	}

	zone, err := time.LoadLocation("Europe/Moscow")
	if err != nil {
		t.Fatal(err)
	}
	today := time.Now().In(zone)
	localAsOf := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, zone)
	tenantID, ownerID := recurringIntegrationUUID(), recurringIntegrationUUID()
	dates := []time.Time{localAsOf.AddDate(0, 0, -19), localAsOf.AddDate(0, 0, -12), localAsOf.AddDate(0, 0, -5)}
	producer := &kafka.Writer{
		Addr: kafka.TCP(brokers...), Topic: sourceTopic, Balancer: &kafka.Hash{},
		MaxAttempts: 5, RequiredAcks: kafka.RequireAll, Async: false, AllowAutoTopicCreation: false,
	}
	defer producer.Close()
	events := make([][]byte, 0, len(dates)+1)
	for index, occurred := range dates {
		transactionID, eventID := recurringIntegrationUUID(), recurringIntegrationUUID()
		value := recurringIntegrationEvent(tenantID, ownerID, transactionID, eventID, occurred, []string{"680.00", "700.00", "720.00"}[index])
		events = append(events, value)
		if err := producer.WriteMessages(ctx, kafka.Message{Key: []byte("transaction:" + transactionID), Value: value}); err != nil {
			t.Fatalf("publish transaction state: %v", err)
		}
	}
	// Replay an existing aggregate version to prove ClickHouse FINAL removes retry duplicates.
	if err := producer.WriteMessages(ctx, kafka.Message{Key: []byte("transaction:replay"), Value: events[0]}); err != nil {
		t.Fatalf("publish duplicate transaction state: %v", err)
	}
	for range len(events) + 1 {
		message, err := reader.FetchMessage(ctx)
		if err != nil {
			t.Fatalf("fetch transaction state: %v", err)
		}
		if err := worker.Process(ctx, recurring.Message{
			Topic: message.Topic, Partition: message.Partition, Offset: message.Offset, Key: message.Key, Value: message.Value,
		}); err != nil {
			t.Fatalf("project and commit transaction state: %v", err)
		}
	}

	history, err := store.Transactions(ctx, tenantID, ownerID, 5000)
	if err != nil {
		t.Fatalf("read deduplicated ClickHouse transaction states: %v", err)
	}
	if len(history) != 3 {
		t.Fatalf("ClickHouse has %d transaction states, want 3 after duplicate replay", len(history))
	}
	projection := recurring.BuildProjection(tenantID, ownerID, history, localAsOf, zone)
	if len(projection.ExpenseSeries) != 1 || len(projection.DueSoon) != 1 || projection.DueSoon[0].DaysUntil != 2 ||
		projection.MonthlyExpenseEstimate == nil || *projection.MonthlyExpenseEstimate != "3000.00" {
		t.Fatalf("recurring projection = %+v, want one series due in two days and monthly estimate 3000.00", projection)
	}
}

func recurringIntegrationEvent(tenantID, ownerID, transactionID, eventID string, financialTime time.Time, amount string) []byte {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	event := map[string]any{
		"event_id": eventID, "event_type": "transaction.created", "schema_version": 1, "tenant_id": tenantID,
		"aggregate_type": "transaction", "aggregate_id": transactionID, "aggregate_version": 1,
		"occurred_at": now, "recorded_at": now, "producer": "core", "correlation_id": recurringIntegrationUUID(),
		"payload": map[string]any{
			"owner_user_id": ownerID, "type": "expense", "amount": amount, "currency": "RUB",
			"category_code": "utilities", "description": "F38 integration internet", "source": "manual",
			"status": "posted", "financial_occurred_at": financialTime.Format(time.RFC3339),
		},
	}
	value, err := json.Marshal(event)
	if err != nil {
		panic(err)
	}
	return value
}

func recurringIntegrationUUID() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		panic(err)
	}
	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80
	encoded := hex.EncodeToString(value)
	return fmt.Sprintf("%s-%s-%s-%s-%s", encoded[:8], encoded[8:12], encoded[12:16], encoded[16:20], encoded[20:])
}

func executeRecurringClickHouse(ctx context.Context, endpoint, database, username, password, query string) error {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return err
	}
	parameters := parsed.Query()
	parameters.Set("database", database)
	parameters.Set("wait_end_of_query", "1")
	parsed.RawQuery = parameters.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, parsed.String(), strings.NewReader(query))
	if err != nil {
		return err
	}
	request.SetBasicAuth(username, password)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("ClickHouse returned HTTP %d: %s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}
