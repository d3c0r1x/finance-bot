package prices

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"strings"
	"time"
)

const invalidReceiptEventCode = "invalid_receipt_confirmed_event"

type ReceiptPriceMessage struct {
	Topic     string
	Partition int
	Offset    int64
	Key       []byte
	Value     []byte
}

type PriceProjectionDeadLetter struct {
	OriginTopic     string `json:"origin_topic"`
	OriginPartition int    `json:"origin_partition"`
	OriginOffset    int64  `json:"origin_offset"`
	ErrorCode       string `json:"error_code"`
	EventID         string `json:"event_id,omitempty"`
	Attempts        int    `json:"attempts"`
}

type ReceiptPriceBroker interface {
	Commit(context.Context, ReceiptPriceMessage) error
	PublishDeadLetter(context.Context, PriceProjectionDeadLetter) error
}

type ProjectionRetryPolicy struct {
	MaxAttempts int
	BaseDelay   time.Duration
	MaxDelay    time.Duration
	Wait        func(context.Context, time.Duration) error
	Jitter      func(time.Duration) time.Duration
}

type ReceiptPriceKafkaWorker struct {
	broker ReceiptPriceBroker
	writer PricePointWriter
	retry  ProjectionRetryPolicy
}

func NewReceiptPriceKafkaWorker(broker ReceiptPriceBroker, writer PricePointWriter,
	retry ProjectionRetryPolicy) (*ReceiptPriceKafkaWorker, error) {
	if broker == nil || writer == nil {
		return nil, errors.New("receipt price worker requires broker and projection writer")
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
		return nil, errors.New("receipt price worker retry policy is invalid")
	}
	if retry.Wait == nil {
		retry.Wait = waitForRetry
	}
	if retry.Jitter == nil {
		retry.Jitter = applyJitter
	}
	return &ReceiptPriceKafkaWorker{broker: broker, writer: writer, retry: retry}, nil
}

// Process commits only after durable projection or dead-letter publication.
// A ClickHouse write followed by an uncertain Kafka commit may replay safely:
// the receipt/item/aggregate-version key is stable in the replacing table.
func (worker *ReceiptPriceKafkaWorker) Process(ctx context.Context, message ReceiptPriceMessage) error {
	points, err := ProjectConfirmedReceiptEvent(message.Value)
	if err != nil {
		var invalid *InvalidReceiptEventError
		if !errors.As(err, &invalid) {
			return err
		}
		deadLetter := PriceProjectionDeadLetter{
			OriginTopic: message.Topic, OriginPartition: message.Partition, OriginOffset: message.Offset,
			ErrorCode: invalidReceiptEventCode, EventID: safeEventID(message.Value), Attempts: 1,
		}
		if publishErr := worker.broker.PublishDeadLetter(ctx, deadLetter); publishErr != nil {
			return fmt.Errorf("publish invalid receipt event to dead letter: %w", publishErr)
		}
		return worker.commit(ctx, message)
	}
	if len(points) > 0 {
		if err := worker.writeWithRetry(ctx, points); err != nil {
			return err
		}
	}
	return worker.commit(ctx, message)
}

func (worker *ReceiptPriceKafkaWorker) writeWithRetry(ctx context.Context, points []PricePoint) error {
	delay := worker.retry.BaseDelay
	for attempt := 1; attempt <= worker.retry.MaxAttempts; attempt++ {
		if err := worker.writer.InsertPricePoints(ctx, points); err == nil {
			return nil
		} else if attempt == worker.retry.MaxAttempts {
			return fmt.Errorf("write receipt price projection after %d attempts: %w", attempt, err)
		}
		if err := worker.retry.Wait(ctx, worker.retry.Jitter(delay)); err != nil {
			return fmt.Errorf("wait before receipt price projection retry: %w", err)
		}
		if delay < worker.retry.MaxDelay/2 {
			delay *= 2
		} else {
			delay = worker.retry.MaxDelay
		}
	}
	return errors.New("receipt price projection retry loop ended unexpectedly")
}

func (worker *ReceiptPriceKafkaWorker) commit(ctx context.Context, message ReceiptPriceMessage) error {
	if err := worker.broker.Commit(ctx, message); err != nil {
		return fmt.Errorf("commit receipt price event offset: %w", err)
	}
	return nil
}

func safeEventID(raw []byte) string {
	var event struct {
		EventID string `json:"event_id"`
	}
	if json.Unmarshal(raw, &event) != nil || !eventUUIDPattern.MatchString(event.EventID) {
		return ""
	}
	return strings.ToLower(event.EventID)
}

func waitForRetry(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func applyJitter(delay time.Duration) time.Duration {
	if delay <= 1 {
		return delay
	}
	return delay/2 + time.Duration(rand.Int63n(int64(delay/2)+1))
}
