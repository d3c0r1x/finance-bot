package prices

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestReceiptPriceKafkaWorkerWritesBeforeCommittingOffset(t *testing.T) {
	var order []string
	writer := &orderedProjectionWriter{order: &order}
	broker := &orderedProjectionBroker{order: &order}
	worker, err := NewReceiptPriceKafkaWorker(broker, writer, ProjectionRetryPolicy{
		MaxAttempts: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	message := ReceiptPriceMessage{Topic: "finance.receipts.v1", Partition: 2, Offset: 91,
		Key: []byte("tenant:receipt"), Value: []byte(confirmedReceiptEvent)}
	if err := worker.Process(context.Background(), message); err != nil {
		t.Fatal(err)
	}
	if strings.Join(order, ",") != "write,commit" {
		t.Fatalf("operation order = %v, want write before commit", order)
	}
	if broker.committed.Offset != message.Offset || len(writer.points) != 1 {
		t.Fatalf("committed=%+v projected=%d, want offset %d and one row", broker.committed, len(writer.points), message.Offset)
	}
}

func TestReceiptPriceKafkaWorkerRetriesSinkBeforeOffsetCommit(t *testing.T) {
	var order []string
	writer := &orderedProjectionWriter{order: &order, failures: 2}
	broker := &orderedProjectionBroker{order: &order}
	var waits []time.Duration
	worker, err := NewReceiptPriceKafkaWorker(broker, writer, ProjectionRetryPolicy{
		MaxAttempts: 3,
		BaseDelay:   10 * time.Millisecond,
		MaxDelay:    100 * time.Millisecond,
		Wait: func(_ context.Context, delay time.Duration) error {
			waits = append(waits, delay)
			return nil
		},
		Jitter: func(delay time.Duration) time.Duration { return delay },
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := worker.Process(context.Background(), ReceiptPriceMessage{
		Topic: "finance.receipts.v1", Partition: 0, Offset: 7, Value: []byte(confirmedReceiptEvent),
	}); err != nil {
		t.Fatal(err)
	}
	if writer.calls != 3 || strings.Join(order, ",") != "write,write,write,commit" {
		t.Fatalf("calls/order = %d/%v, want three writes before commit", writer.calls, order)
	}
	if len(waits) != 2 || waits[0] != 10*time.Millisecond || waits[1] != 20*time.Millisecond {
		t.Fatalf("retry delays = %v, want 10ms then 20ms", waits)
	}
}

func TestReceiptPriceKafkaWorkerDoesNotCommitAfterSinkExhaustion(t *testing.T) {
	var order []string
	writer := &orderedProjectionWriter{order: &order, failures: 5}
	broker := &orderedProjectionBroker{order: &order}
	worker, err := NewReceiptPriceKafkaWorker(broker, writer, ProjectionRetryPolicy{
		MaxAttempts: 5,
		Wait:        func(context.Context, time.Duration) error { return nil },
		Jitter:      func(delay time.Duration) time.Duration { return delay },
	})
	if err != nil {
		t.Fatal(err)
	}
	err = worker.Process(context.Background(), ReceiptPriceMessage{
		Topic: "finance.receipts.v1", Partition: 1, Offset: 44, Value: []byte(confirmedReceiptEvent),
	})
	if err == nil || writer.calls != 5 || len(broker.commits) != 0 {
		t.Fatalf("Process() error=%v writes=%d commits=%v, want five attempts and no offset commit", err, writer.calls, broker.commits)
	}
}

func TestReceiptPriceKafkaWorkerDeadLettersInvalidEventsBeforeCommit(t *testing.T) {
	var order []string
	writer := &orderedProjectionWriter{order: &order}
	broker := &orderedProjectionBroker{order: &order}
	worker, err := NewReceiptPriceKafkaWorker(broker, writer, ProjectionRetryPolicy{MaxAttempts: 1})
	if err != nil {
		t.Fatal(err)
	}
	invalid := strings.Replace(confirmedReceiptEvent, `"receipt.confirmed"`, `"receipt.changed"`, 1)
	message := ReceiptPriceMessage{Topic: "finance.receipts.v1", Partition: 3, Offset: 19,
		Key: []byte("tenant:receipt"), Value: []byte(invalid)}
	if err := worker.Process(context.Background(), message); err != nil {
		t.Fatal(err)
	}
	if strings.Join(order, ",") != "dead-letter,commit" {
		t.Fatalf("operation order = %v, want dead-letter before commit", order)
	}
	if broker.deadLetter.ErrorCode != "invalid_receipt_confirmed_event" || broker.deadLetter.EventID == "" ||
		broker.deadLetter.OriginOffset != message.Offset || broker.deadLetter.OriginTopic != message.Topic {
		t.Fatalf("dead letter = %+v, missing safe source metadata", broker.deadLetter)
	}
	deadLetterJSON, err := json.Marshal(broker.deadLetter)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(deadLetterJSON), "tenant_id") || strings.Contains(string(deadLetterJSON), "owner_user_id") ||
		strings.Contains(string(deadLetterJSON), "Tea 500g") || strings.Contains(string(deadLetterJSON), "Market") {
		t.Fatalf("dead letter exposed financial payload: %s", deadLetterJSON)
	}
	if writer.calls != 0 {
		t.Fatalf("writer calls = %d, want invalid event routed to dead letter", writer.calls)
	}
}

func TestReceiptPriceKafkaWorkerLeavesInvalidOffsetUncommittedWhenDeadLetterFails(t *testing.T) {
	var order []string
	writer := &orderedProjectionWriter{order: &order}
	broker := &orderedProjectionBroker{order: &order, deadLetterErr: errors.New("broker unavailable")}
	worker, err := NewReceiptPriceKafkaWorker(broker, writer, ProjectionRetryPolicy{MaxAttempts: 1})
	if err != nil {
		t.Fatal(err)
	}
	invalid := strings.Replace(confirmedReceiptEvent, `"receipt.confirmed"`, `"receipt.changed"`, 1)
	err = worker.Process(context.Background(), ReceiptPriceMessage{Topic: "finance.receipts.v1", Value: []byte(invalid)})
	if err == nil || len(broker.commits) != 0 {
		t.Fatalf("Process() error=%v commits=%v, want DLQ error and no offset commit", err, broker.commits)
	}
}

type orderedProjectionWriter struct {
	order    *[]string
	calls    int
	failures int
	points   []PricePoint
}

func (writer *orderedProjectionWriter) InsertPricePoints(_ context.Context, points []PricePoint) error {
	writer.calls++
	*writer.order = append(*writer.order, "write")
	if writer.calls <= writer.failures {
		return errors.New("ClickHouse unavailable")
	}
	writer.points = append(writer.points, points...)
	return nil
}

type orderedProjectionBroker struct {
	order         *[]string
	commits       []ReceiptPriceMessage
	committed     ReceiptPriceMessage
	deadLetter    PriceProjectionDeadLetter
	deadLetterErr error
}

func (broker *orderedProjectionBroker) Commit(_ context.Context, message ReceiptPriceMessage) error {
	*broker.order = append(*broker.order, "commit")
	broker.committed = message
	broker.commits = append(broker.commits, message)
	return nil
}

func (broker *orderedProjectionBroker) PublishDeadLetter(_ context.Context, message PriceProjectionDeadLetter) error {
	*broker.order = append(*broker.order, "dead-letter")
	broker.deadLetter = message
	return broker.deadLetterErr
}
