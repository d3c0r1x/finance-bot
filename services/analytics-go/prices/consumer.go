package prices

import (
	"context"
	"errors"
)

type PricePointWriter interface {
	InsertPricePoints(context.Context, []PricePoint) error
}

type ReceiptProjectionConsumer struct {
	writer PricePointWriter
}

func NewReceiptProjectionConsumer(writer PricePointWriter) (*ReceiptProjectionConsumer, error) {
	if writer == nil {
		return nil, errors.New("receipt projection consumer requires a price point writer")
	}
	return &ReceiptProjectionConsumer{writer: writer}, nil
}

// HandleConfirmedReceipt returns storage failures to the caller so an at-least-once
// broker consumer can retry without committing its offset. Replay rows retain the
// same receipt/item/version key and are collapsed by the ClickHouse table engine.
func (consumer *ReceiptProjectionConsumer) HandleConfirmedReceipt(ctx context.Context, event []byte) error {
	points, err := ProjectConfirmedReceiptEvent(event)
	if err != nil {
		return err
	}
	if len(points) == 0 {
		return nil
	}
	return consumer.writer.InsertPricePoints(ctx, points)
}
