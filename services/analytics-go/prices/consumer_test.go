package prices

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type recordingPricePointWriter struct {
	calls  int
	points []PricePoint
	err    error
}

func (writer *recordingPricePointWriter) InsertPricePoints(_ context.Context, points []PricePoint) error {
	writer.calls++
	writer.points = append(writer.points, points...)
	return writer.err
}

func TestReceiptProjectionConsumerPersistsReplaySafeEventIdentity(t *testing.T) {
	writer := &recordingPricePointWriter{}
	consumer, err := NewReceiptProjectionConsumer(writer)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := consumer.HandleConfirmedReceipt(context.Background(), []byte(confirmedReceiptEvent)); err != nil {
			t.Fatal(err)
		}
	}
	if writer.calls != 2 || len(writer.points) != 2 {
		t.Fatalf("writer calls/points = %d/%d, want repeated at-least-once delivery", writer.calls, len(writer.points))
	}
	first, replay := writer.points[0], writer.points[1]
	if first.EventID != replay.EventID || first.AggregateVersion != replay.AggregateVersion || first.ItemID != replay.ItemID {
		t.Fatalf("replay identity changed: first=%+v replay=%+v", first, replay)
	}
}

func TestReceiptProjectionConsumerSkipsEventsWithNoUsablePriceLines(t *testing.T) {
	writer := &recordingPricePointWriter{}
	consumer, err := NewReceiptProjectionConsumer(writer)
	if err != nil {
		t.Fatal(err)
	}
	eventWithoutUsableLines := strings.Replace(confirmedReceiptEvent, `"2.000000"`, `null`, 1)
	if err := consumer.HandleConfirmedReceipt(context.Background(), []byte(eventWithoutUsableLines)); err != nil {
		t.Fatal(err)
	}
	if writer.calls != 0 {
		t.Fatalf("writer calls = %d, want no empty ClickHouse insert", writer.calls)
	}
}

func TestReceiptProjectionConsumerPropagatesStorageFailureForRetry(t *testing.T) {
	wantErr := errors.New("ClickHouse unavailable")
	writer := &recordingPricePointWriter{err: wantErr}
	consumer, err := NewReceiptProjectionConsumer(writer)
	if err != nil {
		t.Fatal(err)
	}
	if err := consumer.HandleConfirmedReceipt(context.Background(), []byte(confirmedReceiptEvent)); !errors.Is(err, wantErr) {
		t.Fatalf("HandleConfirmedReceipt() error = %v, want storage error for retry", err)
	}
}
