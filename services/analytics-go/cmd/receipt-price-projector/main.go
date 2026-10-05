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
	"github.com/segmentio/kafka-go"
)

func main() {
	if err := run(); err != nil {
		log.Print("service=analytics-price-projector error_code=worker_failed")
		os.Exit(1)
	}
}

func run() error {
	config, err := loadKafkaRuntimeConfig(os.Getenv)
	if err != nil {
		return errors.New("analytics.price_projection.kafka_configuration_invalid")
	}
	store, err := prices.NewClickHouseHTTPStore(prices.ClickHouseHTTPConfig{
		Endpoint: os.Getenv("CLICKHOUSE_URL"), Database: envOr("CLICKHOUSE_DATABASE", "finance_analytics"),
		Username: os.Getenv("CLICKHOUSE_USERNAME"), Password: os.Getenv("CLICKHOUSE_PASSWORD"),
	})
	if err != nil {
		return errors.New("analytics.price_projection.clickhouse_configuration_invalid")
	}
	startup, cancelStartup := context.WithTimeout(context.Background(), 5*time.Second)
	if err := store.Ping(startup); err != nil {
		cancelStartup()
		return errors.New("analytics.price_projection.clickhouse_unavailable")
	}
	cancelStartup()

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: config.Brokers, Topic: config.Topic, GroupID: config.GroupID, Dialer: config.Dialer,
		MinBytes: 1, MaxBytes: 1 << 20, MaxWait: time.Second, CommitInterval: 0,
		IsolationLevel: kafka.ReadCommitted, StartOffset: kafka.FirstOffset,
	})
	deadLetterWriter := &kafka.Writer{
		Addr: kafka.TCP(config.Brokers...), Topic: config.DeadLetterTopic, Balancer: &kafka.Hash{},
		MaxAttempts: 5, RequiredAcks: kafka.RequireAll, Async: false,
		AllowAutoTopicCreation: false, Transport: config.Transport,
	}
	defer reader.Close()
	defer deadLetterWriter.Close()
	broker := &kafkaReceiptPriceBroker{reader: reader, deadLetter: deadLetterWriter}
	worker, err := prices.NewReceiptPriceKafkaWorker(broker, store, prices.ProjectionRetryPolicy{})
	if err != nil {
		return errors.New("analytics.price_projection.worker_configuration_invalid")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log.Print("service=analytics-price-projector status=started")
	if err := consume(ctx, reader, worker); err != nil {
		return errors.New("analytics.price_projection.consume_failed")
	}
	log.Print("service=analytics-price-projector status=stopped")
	return nil
}

type messageFetcher interface {
	FetchMessage(context.Context) (kafka.Message, error)
}

func consume(ctx context.Context, reader messageFetcher, worker *prices.ReceiptPriceKafkaWorker) error {
	for {
		message, err := reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		projectedMessage := prices.ReceiptPriceMessage{
			Topic: message.Topic, Partition: message.Partition, Offset: message.Offset,
			Key: message.Key, Value: message.Value,
		}
		if err := worker.Process(ctx, projectedMessage); err != nil {
			return err
		}
	}
}

type kafkaReceiptPriceBroker struct {
	reader     *kafka.Reader
	deadLetter *kafka.Writer
}

func (broker *kafkaReceiptPriceBroker) Commit(ctx context.Context, message prices.ReceiptPriceMessage) error {
	return broker.reader.CommitMessages(ctx, kafka.Message{
		Topic: message.Topic, Partition: message.Partition, Offset: message.Offset,
	})
}

func (broker *kafkaReceiptPriceBroker) PublishDeadLetter(ctx context.Context,
	deadLetter prices.PriceProjectionDeadLetter) error {
	value, err := json.Marshal(deadLetter)
	if err != nil {
		return errors.New("encode safe price projection dead letter")
	}
	key := []byte(fmt.Sprintf("%s:%d:%d", deadLetter.OriginTopic,
		deadLetter.OriginPartition, deadLetter.OriginOffset))
	return broker.deadLetter.WriteMessages(ctx, kafka.Message{Key: key, Value: value})
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
