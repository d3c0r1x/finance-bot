package main

import (
	"crypto/tls"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/sasl"
	"github.com/segmentio/kafka-go/sasl/plain"
	"github.com/segmentio/kafka-go/sasl/scram"
)

type kafkaRuntimeConfig struct {
	Brokers         []string
	Topic           string
	GroupID         string
	DeadLetterTopic string
	Dialer          *kafka.Dialer
	Transport       *kafka.Transport
}

func loadKafkaRuntimeConfig(lookup func(string) string) (kafkaRuntimeConfig, error) {
	brokers := splitBrokers(lookup("KAFKA_BROKERS"))
	if len(brokers) == 0 {
		return kafkaRuntimeConfig{}, errors.New("KAFKA_BROKERS must list at least one broker")
	}
	topic := valueOr(lookup("KAFKA_RECEIPT_TOPIC"), "finance.receipts.v1")
	groupID := valueOr(lookup("KAFKA_PRICE_GROUP_ID"), "analytics-price-projection-v1")
	deadLetterTopic := valueOr(lookup("KAFKA_RECEIPT_DLQ_TOPIC"), topic+".dlq")
	if topic == deadLetterTopic || groupID == "" {
		return kafkaRuntimeConfig{}, errors.New("Kafka topic and consumer group configuration is invalid")
	}

	useTLS := true
	if rawTLS := strings.TrimSpace(lookup("KAFKA_TLS")); rawTLS != "" {
		switch strings.ToLower(rawTLS) {
		case "true", "1", "yes":
			useTLS = true
		case "false", "0", "no":
			useTLS = false
		default:
			return kafkaRuntimeConfig{}, errors.New("KAFKA_TLS must be true or false")
		}
	}
	username := strings.TrimSpace(lookup("KAFKA_USERNAME"))
	password := lookup("KAFKA_PASSWORD")
	if (username == "") != (password == "") {
		return kafkaRuntimeConfig{}, errors.New("KAFKA_USERNAME and KAFKA_PASSWORD must be set together")
	}
	var mechanism sasl.Mechanism
	if username != "" {
		if !useTLS {
			return kafkaRuntimeConfig{}, errors.New("Kafka SASL credentials require KAFKA_TLS=true")
		}
		var err error
		switch strings.ToUpper(strings.TrimSpace(lookup("KAFKA_SASL_MECHANISM"))) {
		case "PLAIN":
			mechanism = plain.Mechanism{Username: username, Password: password}
		case "SCRAM-SHA-256":
			mechanism, err = scram.Mechanism(scram.SHA256, username, password)
		case "SCRAM-SHA-512":
			mechanism, err = scram.Mechanism(scram.SHA512, username, password)
		default:
			return kafkaRuntimeConfig{}, errors.New("KAFKA_SASL_MECHANISM must be PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512")
		}
		if err != nil {
			return kafkaRuntimeConfig{}, fmt.Errorf("configure Kafka SASL: %w", err)
		}
	}
	var tlsConfig *tls.Config
	if useTLS {
		tlsConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	}
	dialer := &kafka.Dialer{
		Timeout: 10 * time.Second, DualStack: true, TLS: tlsConfig, SASLMechanism: mechanism,
	}
	transport := &kafka.Transport{TLS: tlsConfig, SASL: mechanism, ClientID: "finance-analytics-price-projector"}
	return kafkaRuntimeConfig{
		Brokers: brokers, Topic: topic, GroupID: groupID, DeadLetterTopic: deadLetterTopic,
		Dialer: dialer, Transport: transport,
	}, nil
}

func splitBrokers(raw string) []string {
	parts := strings.Split(raw, ",")
	brokers := make([]string, 0, len(parts))
	for _, part := range parts {
		broker := strings.TrimSpace(part)
		if broker == "" {
			return nil
		}
		brokers = append(brokers, broker)
	}
	return brokers
}

func valueOr(value, fallback string) string {
	if trimmed := strings.TrimSpace(value); trimmed != "" {
		return trimmed
	}
	return fallback
}
