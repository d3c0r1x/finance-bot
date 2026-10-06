package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/prices"
	"github.com/d3c0r1x/finance-bot/services/analytics-go/recurring"
	"github.com/segmentio/kafka-go"
)

func main() {
	if err := run(); err != nil {
		log.Print("service=analytics-recurring-projector error_code=worker_failed")
		os.Exit(1)
	}
}

func run() error {
	config, err := loadKafkaRuntimeConfig(os.Getenv)
	if err != nil {
		return errors.New("analytics.recurring.kafka_configuration_invalid")
	}
	store, err := prices.NewClickHouseHTTPStore(prices.ClickHouseHTTPConfig{
		Endpoint: os.Getenv("CLICKHOUSE_URL"), Database: envOr("CLICKHOUSE_DATABASE", "finance_analytics"),
		Username: os.Getenv("CLICKHOUSE_USERNAME"), Password: os.Getenv("CLICKHOUSE_PASSWORD"),
	})
	if err != nil {
		return errors.New("analytics.recurring.clickhouse_configuration_invalid")
	}
	startup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	if err := store.Ping(startup); err != nil {
		cancel()
		return errors.New("analytics.recurring.clickhouse_unavailable")
	}
	cancel()
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: config.Brokers, Topic: config.Topic, GroupID: config.GroupID, Dialer: config.Dialer,
		MinBytes: 1, MaxBytes: 1 << 20, MaxWait: time.Second, CommitInterval: 0,
		IsolationLevel: kafka.ReadCommitted, StartOffset: kafka.FirstOffset,
	})
	deadLetterWriter := &kafka.Writer{
		Addr: kafka.TCP(config.Brokers...), Topic: config.DeadLetterTopic, Balancer: &kafka.Hash{},
		MaxAttempts: 5, RequiredAcks: kafka.RequireAll, Async: false, AllowAutoTopicCreation: false, Transport: config.Transport,
	}
	defer reader.Close()
	defer deadLetterWriter.Close()
	worker, err := recurring.NewKafkaWorker(&kafkaRecurringBroker{reader: reader, deadLetter: deadLetterWriter}, store, recurring.RetryPolicy{})
	if err != nil {
		return errors.New("analytics.recurring.worker_configuration_invalid")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log.Print("service=analytics-recurring-projector status=started")
	if err := consume(ctx, reader, worker); err != nil {
		return errors.New("analytics.recurring.consume_failed")
	}
	log.Print("service=analytics-recurring-projector status=stopped")
	return nil
}

type messageFetcher interface {
	FetchMessage(context.Context) (kafka.Message, error)
}

func consume(ctx context.Context, reader messageFetcher, worker *recurring.KafkaWorker) error {
	for {
		message, err := reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		projected := recurring.Message{Topic: message.Topic, Partition: message.Partition, Offset: message.Offset, Key: message.Key, Value: message.Value}
		if err := worker.Process(ctx, projected); err != nil {
			return err
		}
	}
}

type kafkaRecurringBroker struct {
	reader     *kafka.Reader
	deadLetter *kafka.Writer
}

func (broker *kafkaRecurringBroker) Commit(ctx context.Context, message recurring.Message) error {
	return broker.reader.CommitMessages(ctx, kafka.Message{Topic: message.Topic, Partition: message.Partition, Offset: message.Offset})
}
func (broker *kafkaRecurringBroker) PublishDeadLetter(ctx context.Context, item recurring.DeadLetter) error {
	value, err := json.Marshal(item)
	if err != nil {
		return errors.New("encode safe recurring dead letter")
	}
	key := []byte(fmt.Sprintf("%s:%d:%d", item.OriginTopic, item.OriginPartition, item.OriginOffset))
	return broker.deadLetter.WriteMessages(ctx, kafka.Message{Key: key, Value: value})
}
func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
