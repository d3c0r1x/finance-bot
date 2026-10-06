package recurring

import (
	"context"
	"errors"
	"testing"
	"time"
)

type workerRecorder struct {
	calls        []string
	transactions []Transaction
	dead         []DeadLetter
	failures     int
	commitErr    error
	dlqErr       error
}

func (recorder *workerRecorder) InsertTransactions(_ context.Context, rows []Transaction) error {
	recorder.calls = append(recorder.calls, "write")
	if recorder.failures > 0 {
		recorder.failures--
		return errors.New("temporary store failure")
	}
	recorder.transactions = append(recorder.transactions, rows...)
	return nil
}
func (recorder *workerRecorder) Commit(context.Context, Message) error {
	recorder.calls = append(recorder.calls, "commit")
	return recorder.commitErr
}
func (recorder *workerRecorder) PublishDeadLetter(_ context.Context, dead DeadLetter) error {
	recorder.calls = append(recorder.calls, "dlq")
	recorder.dead = append(recorder.dead, dead)
	return recorder.dlqErr
}

func TestKafkaWorkerWritesBeforeCommitAndRetriesTransientStorageErrors(t *testing.T) {
	recorder := &workerRecorder{failures: 1}
	worker, err := NewKafkaWorker(recorder, recorder, RetryPolicy{
		MaxAttempts: 2, BaseDelay: time.Millisecond, MaxDelay: time.Millisecond,
		Wait: func(context.Context, time.Duration) error { return nil }, Jitter: func(d time.Duration) time.Duration { return d },
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := worker.Process(context.Background(), Message{Topic: "finance.transactions.v1", Partition: 1, Offset: 12, Value: []byte(transactionStateEvent)}); err != nil {
		t.Fatal(err)
	}
	if len(recorder.transactions) != 1 || len(recorder.calls) != 3 || recorder.calls[0] != "write" || recorder.calls[1] != "write" || recorder.calls[2] != "commit" {
		t.Fatalf("calls=%v transactions=%+v", recorder.calls, recorder.transactions)
	}
}

func TestKafkaWorkerDeadLettersContractErrorsWithoutPayloadAndDoesNotCommitOnDLQFailure(t *testing.T) {
	bad := []byte(`{"event_id":"00000000-0000-4000-8000-000000000201","payload":{"private":"do not copy"}}`)
	recorder := &workerRecorder{}
	worker, err := NewKafkaWorker(recorder, recorder, RetryPolicy{Wait: func(context.Context, time.Duration) error { return nil }, Jitter: func(d time.Duration) time.Duration { return d }})
	if err != nil {
		t.Fatal(err)
	}
	message := Message{Topic: "finance.transactions.v1", Partition: 2, Offset: 99, Value: bad}
	if err := worker.Process(context.Background(), message); err != nil {
		t.Fatal(err)
	}
	if len(recorder.dead) != 1 || recorder.dead[0].ErrorCode != InvalidEventCode || recorder.dead[0].EventID != "00000000-0000-4000-8000-000000000201" ||
		len(recorder.calls) != 2 || recorder.calls[0] != "dlq" || recorder.calls[1] != "commit" {
		t.Fatalf("dead=%+v calls=%v", recorder.dead, recorder.calls)
	}
	recorder.calls = nil
	recorder.dlqErr = errors.New("DLQ offline")
	if err := worker.Process(context.Background(), message); err == nil {
		t.Fatal("DLQ failure was swallowed")
	}
	if len(recorder.calls) != 1 || recorder.calls[0] != "dlq" {
		t.Fatalf("committed without durable DLQ: %v", recorder.calls)
	}
}

func TestKafkaWorkerLeavesOffsetUncommittedWhenProjectionWriteExhaustsRetries(t *testing.T) {
	recorder := &workerRecorder{failures: 2}
	worker, err := NewKafkaWorker(recorder, recorder, RetryPolicy{
		MaxAttempts: 2, BaseDelay: time.Millisecond, MaxDelay: time.Millisecond,
		Wait: func(context.Context, time.Duration) error { return nil }, Jitter: func(d time.Duration) time.Duration { return d },
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := worker.Process(context.Background(), Message{Value: []byte(transactionStateEvent)}); err == nil {
		t.Fatal("projection failure was swallowed")
	}
	if len(recorder.calls) != 2 || recorder.calls[0] != "write" || recorder.calls[1] != "write" {
		t.Fatalf("unexpected calls %v", recorder.calls)
	}
}
