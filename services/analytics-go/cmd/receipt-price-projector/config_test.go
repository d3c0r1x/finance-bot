package main

import "testing"

func TestKafkaRuntimeConfigDefaultsToReceiptProjectionAndTLS(t *testing.T) {
	config, err := loadKafkaRuntimeConfig(func(key string) string {
		if key == "KAFKA_BROKERS" {
			return " broker-a:9093,broker-b:9093 "
		}
		return ""
	})
	if err != nil {
		t.Fatal(err)
	}
	if config.Topic != "finance.receipts.v1" || config.GroupID != "analytics-price-projection-v1" ||
		config.DeadLetterTopic != "finance.receipts.v1.dlq" {
		t.Fatalf("Kafka topics/group = %q/%q/%q", config.Topic, config.GroupID, config.DeadLetterTopic)
	}
	if len(config.Brokers) != 2 || config.Brokers[0] != "broker-a:9093" || config.Dialer.TLS == nil ||
		config.Transport.TLS == nil {
		t.Fatalf("Kafka connection defaults are incomplete: %+v", config)
	}
}

func TestKafkaRuntimeConfigRequiresTLSForCredentialsAndSupportsScram(t *testing.T) {
	values := map[string]string{
		"KAFKA_BROKERS": "broker:9093", "KAFKA_USERNAME": "analytics", "KAFKA_PASSWORD": "secret",
		"KAFKA_SASL_MECHANISM": "SCRAM-SHA-512",
	}
	lookup := func(key string) string { return values[key] }
	if _, err := loadKafkaRuntimeConfig(func(key string) string {
		if key == "KAFKA_TLS" {
			return "false"
		}
		return lookup(key)
	}); err == nil {
		t.Fatal("Kafka credentials were allowed without TLS")
	}
	config, err := loadKafkaRuntimeConfig(lookup)
	if err != nil {
		t.Fatal(err)
	}
	if config.Dialer.SASLMechanism == nil || config.Transport.SASL == nil {
		t.Fatal("SCRAM credentials were not applied to reader and writer")
	}
}

func TestKafkaRuntimeConfigRejectsPartialCredentialsAndInvalidBrokerList(t *testing.T) {
	for name, values := range map[string]map[string]string{
		"missing broker": {"KAFKA_BROKERS": ""},
		"empty broker":   {"KAFKA_BROKERS": "broker:9092,"},
		"partial auth":   {"KAFKA_BROKERS": "broker:9092", "KAFKA_USERNAME": "analytics"},
		"unknown SASL":   {"KAFKA_BROKERS": "broker:9092", "KAFKA_USERNAME": "analytics", "KAFKA_PASSWORD": "secret", "KAFKA_SASL_MECHANISM": "OAUTH"},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := loadKafkaRuntimeConfig(func(key string) string { return values[key] }); err == nil {
				t.Fatal("invalid Kafka configuration was accepted")
			}
		})
	}
}
