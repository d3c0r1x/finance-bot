package recurring

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"strings"
	"time"
)

type Message struct {
	Topic     string
	Partition int
	Offset    int64
	Key       []byte
	Value     []byte
}

type DeadLetter struct {
	OriginTopic     string `json:"origin_topic"`
	OriginPartition int    `json:"origin_partition"`
	OriginOffset    int64  `json:"origin_offset"`
	ErrorCode       string `json:"error_code"`
	EventID         string `json:"event_id,omitempty"`
	Attempts        int    `json:"attempts"`
}

type Broker interface {
	Commit(context.Context, Message) error
	PublishDeadLetter(context.Context, DeadLetter) error
}

type TransactionWriter interface {
	InsertTransactions(context.Context, []Transaction) error
}

type RetryPolicy struct {
	MaxAttempts int
	BaseDelay   time.Duration
	MaxDelay    time.Duration
	Wait        func(context.Context, time.Duration) error
	Jitter      func(time.Duration) time.Duration
}

type KafkaWorker struct {
	broker Broker
	writer TransactionWriter
	retry  RetryPolicy
}

func NewKafkaWorker(broker Broker, writer TransactionWriter, retry RetryPolicy) (*KafkaWorker, error) {
	if broker == nil || writer == nil {
		return nil, errors.New("recurring worker requires broker and projection writer")
	}
	if retry.MaxAttempts == 0 {
		retry.MaxAttempts = 5
	}
	if retry.BaseDelay == 0 {
		retry.BaseDelay = 100 * time.Millisecond
	}
	if retry.MaxDelay == 0 {
		retry.MaxDelay = 5 * time.Second
	}
	if retry.MaxAttempts < 1 || retry.BaseDelay < 0 || retry.MaxDelay < retry.BaseDelay {
		return nil, errors.New("recurring worker retry policy is invalid")
	}
	if retry.Wait == nil {
		retry.Wait = waitRetry
	}
	if retry.Jitter == nil {
		retry.Jitter = jitterDelay
	}
	return &KafkaWorker{broker: broker, writer: writer, retry: retry}, nil
}

// Process projects first and commits only after persistence or a safe DLQ write.
func (worker *KafkaWorker) Process(ctx context.Context, message Message) error {
	transaction, err := ProjectTransactionStateEvent(message.Value)
	if err != nil {
		var invalid *InvalidEventError
		if !errors.As(err, &invalid) {
			return err
		}
		deadLetter := DeadLetter{
			OriginTopic: message.Topic, OriginPartition: message.Partition, OriginOffset: message.Offset,
			ErrorCode: InvalidEventCode, EventID: safeEventID(message.Value), Attempts: 1,
		}
		if err := worker.broker.PublishDeadLetter(ctx, deadLetter); err != nil {
			return fmt.Errorf("publish invalid transaction event to dead letter: %w", err)
		}
		return worker.commit(ctx, message)
	}
	if err := worker.write(ctx, transaction); err != nil {
		return err
	}
	return worker.commit(ctx, message)
}

func (worker *KafkaWorker) write(ctx context.Context, transaction Transaction) error {
	delay := worker.retry.BaseDelay
	for attempt := 1; attempt <= worker.retry.MaxAttempts; attempt++ {
		if err := worker.writer.InsertTransactions(ctx, []Transaction{transaction}); err == nil {
			return nil
		} else if attempt == worker.retry.MaxAttempts {
			return fmt.Errorf("write recurring transaction projection after %d attempts: %w", attempt, err)
		}
		if err := worker.retry.Wait(ctx, worker.retry.Jitter(delay)); err != nil {
			return fmt.Errorf("wait before recurring projection retry: %w", err)
		}
		if delay < worker.retry.MaxDelay/2 {
			delay *= 2
		} else {
			delay = worker.retry.MaxDelay
		}
	}
	return errors.New("recurring projection retry loop ended unexpectedly")
}

func (worker *KafkaWorker) commit(ctx context.Context, message Message) error {
	if err := worker.broker.Commit(ctx, message); err != nil {
		return fmt.Errorf("commit recurring transaction event offset: %w", err)
	}
	return nil
}

func safeEventID(raw []byte) string {
	var event struct {
		EventID string `json:"event_id"`
	}
	if json.Unmarshal(raw, &event) != nil || !uuidPattern.MatchString(event.EventID) {
		return ""
	}
	return strings.ToLower(event.EventID)
}

func waitRetry(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func jitterDelay(delay time.Duration) time.Duration {
	if delay <= 1 {
		return delay
	}
	return delay/2 + time.Duration(rand.Int63n(int64(delay/2)+1))
}
