package prices

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"time"
)

var eventUUIDPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type PricePoint struct {
	TenantID         string
	OwnerID          string
	ReceiptID        string
	TransactionID    string
	ItemID           string
	EventID          string
	AggregateVersion uint64
	ReceiptDate      string
	PurchasedAt      time.Time
	RecordedAt       time.Time
	Merchant         *string
	Name             string
	Quantity         string
	LineSum          string
	UnitPrice        string
}

type receiptConfirmedEvent struct {
	EventID          string                  `json:"event_id"`
	EventType        string                  `json:"event_type"`
	SchemaVersion    int                     `json:"schema_version"`
	TenantID         string                  `json:"tenant_id"`
	AggregateType    string                  `json:"aggregate_type"`
	AggregateID      string                  `json:"aggregate_id"`
	AggregateVersion uint64                  `json:"aggregate_version"`
	OccurredAt       string                  `json:"occurred_at"`
	RecordedAt       string                  `json:"recorded_at"`
	Producer         string                  `json:"producer"`
	CorrelationID    string                  `json:"correlation_id"`
	Payload          receiptConfirmedPayload `json:"payload"`
}

type receiptConfirmedPayload struct {
	OwnerID       string                 `json:"owner_user_id"`
	TransactionID string                 `json:"transaction_id"`
	ReceiptDate   string                 `json:"receipt_date"`
	Currency      string                 `json:"currency"`
	Merchant      *string                `json:"merchant"`
	Items         []receiptConfirmedItem `json:"items"`
}

type receiptConfirmedItem struct {
	ItemID   string  `json:"item_id"`
	Name     string  `json:"name"`
	Quantity *string `json:"quantity"`
	LineSum  *string `json:"line_sum"`
}

type InvalidReceiptEventError struct {
	Cause error
}

func (eventErr *InvalidReceiptEventError) Error() string {
	return "invalid confirmed receipt event: " + eventErr.Cause.Error()
}

func (eventErr *InvalidReceiptEventError) Unwrap() error {
	return eventErr.Cause
}

// ProjectConfirmedReceiptEvent validates a Core receipt event and converts its
// usable paid lines into immutable price points. Invalid price lines are skipped
// without rejecting the event or affecting other consumers of its snapshot.
func ProjectConfirmedReceiptEvent(raw []byte) ([]PricePoint, error) {
	points, err := projectConfirmedReceiptEvent(raw)
	if err != nil {
		return nil, &InvalidReceiptEventError{Cause: err}
	}
	return points, nil
}

func projectConfirmedReceiptEvent(raw []byte) ([]PricePoint, error) {
	if err := validateReceiptEventShape(raw); err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var event receiptConfirmedEvent
	if err := decoder.Decode(&event); err != nil {
		return nil, fmt.Errorf("decode confirmed receipt event: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return nil, errors.New("confirmed receipt event contains trailing JSON")
	}
	if event.EventType != "receipt.confirmed" || event.SchemaVersion != 1 ||
		event.AggregateType != "receipt" || event.Producer != "core" || event.AggregateVersion == 0 {
		return nil, errors.New("unsupported confirmed receipt event envelope")
	}
	for field, value := range map[string]string{
		"event_id": event.EventID, "tenant_id": event.TenantID, "aggregate_id": event.AggregateID,
		"owner_user_id": event.Payload.OwnerID, "transaction_id": event.Payload.TransactionID,
	} {
		if !eventUUIDPattern.MatchString(value) {
			return nil, fmt.Errorf("confirmed receipt event has invalid %s", field)
		}
	}
	if event.Payload.Currency != "RUB" {
		return nil, errors.New("confirmed receipt event currency is unsupported")
	}
	if _, err := time.Parse(time.DateOnly, event.Payload.ReceiptDate); err != nil {
		return nil, errors.New("confirmed receipt event has invalid receipt_date")
	}
	purchasedAt, err := time.Parse(time.RFC3339Nano, event.OccurredAt)
	if err != nil {
		return nil, errors.New("confirmed receipt event has invalid occurred_at")
	}
	recordedAt, err := time.Parse(time.RFC3339Nano, event.RecordedAt)
	if err != nil {
		return nil, errors.New("confirmed receipt event has invalid recorded_at")
	}

	points := make([]PricePoint, 0, len(event.Payload.Items))
	for _, item := range event.Payload.Items {
		if !eventUUIDPattern.MatchString(item.ItemID) || strings.TrimSpace(item.Name) == "" {
			return nil, errors.New("confirmed receipt event has invalid item identity")
		}
		if item.Quantity == nil || item.LineSum == nil {
			continue
		}
		unitPrice, priceErr := UnitPrice(*item.LineSum, *item.Quantity)
		if priceErr != nil {
			continue
		}
		points = append(points, PricePoint{
			TenantID: event.TenantID, OwnerID: event.Payload.OwnerID, ReceiptID: event.AggregateID,
			TransactionID: event.Payload.TransactionID, ItemID: item.ItemID, EventID: event.EventID,
			AggregateVersion: event.AggregateVersion, ReceiptDate: event.Payload.ReceiptDate,
			PurchasedAt: purchasedAt, RecordedAt: recordedAt, Merchant: event.Payload.Merchant,
			Name: item.Name, Quantity: *item.Quantity, LineSum: *item.LineSum, UnitPrice: unitPrice,
		})
	}
	return points, nil
}

func validateReceiptEventShape(raw []byte) error {
	var envelope map[string]json.RawMessage
	if err := json.Unmarshal(raw, &envelope); err != nil || envelope == nil {
		return errors.New("confirmed receipt event must be a JSON object")
	}
	if err := requireJSONFields(envelope, "event_id", "event_type", "schema_version", "tenant_id", "aggregate_type",
		"aggregate_id", "aggregate_version", "occurred_at", "recorded_at", "producer", "payload"); err != nil {
		return err
	}
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(envelope["payload"], &payload); err != nil || payload == nil {
		return errors.New("confirmed receipt event payload must be a JSON object")
	}
	if err := requireJSONFields(payload, "owner_user_id", "transaction_id", "receipt_date", "currency", "merchant", "items"); err != nil {
		return err
	}
	var items []json.RawMessage
	if err := json.Unmarshal(payload["items"], &items); err != nil || items == nil {
		return errors.New("confirmed receipt event items must be a JSON array")
	}
	for _, rawItem := range items {
		var item map[string]json.RawMessage
		if err := json.Unmarshal(rawItem, &item); err != nil || item == nil {
			return errors.New("confirmed receipt event item must be a JSON object")
		}
		if err := requireJSONFields(item, "item_id", "name", "quantity", "line_sum"); err != nil {
			return err
		}
	}
	return nil
}

func requireJSONFields(object map[string]json.RawMessage, fields ...string) error {
	for _, field := range fields {
		if _, present := object[field]; !present {
			return fmt.Errorf("confirmed receipt event is missing %s", field)
		}
	}
	return nil
}
