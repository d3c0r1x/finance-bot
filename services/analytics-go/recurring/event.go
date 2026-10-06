package recurring

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

var uuidPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var canonicalAmountPattern = regexp.MustCompile(`^(?:0\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\.[0-9]{1,2})?)$`)

const InvalidEventCode = "invalid_transaction_state_event"

// InvalidEventError marks a contract-invalid event that is safe to dead-letter.
type InvalidEventError struct{ Cause error }

func (eventErr *InvalidEventError) Error() string {
	return "invalid transaction state event: " + eventErr.Cause.Error()
}
func (eventErr *InvalidEventError) Unwrap() error { return eventErr.Cause }

type transactionEnvelope struct {
	EventID          string             `json:"event_id"`
	EventType        string             `json:"event_type"`
	SchemaVersion    int                `json:"schema_version"`
	TenantID         string             `json:"tenant_id"`
	AggregateType    string             `json:"aggregate_type"`
	AggregateID      string             `json:"aggregate_id"`
	AggregateVersion uint64             `json:"aggregate_version"`
	OccurredAt       string             `json:"occurred_at"`
	RecordedAt       string             `json:"recorded_at"`
	Producer         string             `json:"producer"`
	CorrelationID    string             `json:"correlation_id"`
	Payload          transactionPayload `json:"payload"`
}

type transactionPayload struct {
	OwnerID             string `json:"owner_user_id"`
	Type                string `json:"type"`
	Amount              string `json:"amount"`
	Currency            string `json:"currency"`
	CategoryCode        string `json:"category_code"`
	Description         string `json:"description"`
	Source              string `json:"source"`
	Status              string `json:"status"`
	FinancialOccurredAt string `json:"financial_occurred_at"`
	DebtID              string `json:"debt_id"`
	DebtBalanceEffect   string `json:"debt_balance_effect"`
}

// ProjectTransactionStateEvent validates a full snapshot from finance.transactions.v1.
func ProjectTransactionStateEvent(raw []byte) (Transaction, error) {
	invalid := func(err error) (Transaction, error) {
		return Transaction{}, &InvalidEventError{Cause: err}
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return invalid(errors.New("event is empty"))
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var event transactionEnvelope
	if err := decoder.Decode(&event); err != nil {
		return invalid(fmt.Errorf("decode event envelope: %w", err))
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return invalid(errors.New("event contains trailing JSON"))
	}
	if event.EventID == "" || event.TenantID == "" || event.AggregateID == "" || event.Payload.OwnerID == "" ||
		event.EventType == "" || event.AggregateType == "" || event.Producer == "" || event.OccurredAt == "" ||
		event.RecordedAt == "" || event.Payload.Type == "" || event.Payload.Amount == "" ||
		event.Payload.Currency == "" || event.Payload.CategoryCode == "" || event.Payload.Status == "" ||
		event.Payload.FinancialOccurredAt == "" {
		return invalid(errors.New("event is missing a required field"))
	}
	if event.EventType != "transaction.created" && event.EventType != "transaction.updated" && event.EventType != "transaction.voided" {
		return invalid(errors.New("unsupported transaction event type"))
	}
	if event.SchemaVersion != 1 || event.AggregateType != "transaction" || event.Producer != "core" || event.AggregateVersion == 0 {
		return invalid(errors.New("unsupported event envelope"))
	}
	for name, value := range map[string]string{
		"event_id": event.EventID, "tenant_id": event.TenantID, "aggregate_id": event.AggregateID,
		"owner_user_id": event.Payload.OwnerID,
	} {
		if !uuidPattern.MatchString(value) {
			return invalid(fmt.Errorf("invalid %s", name))
		}
	}
	if event.Payload.Type != "expense" && event.Payload.Type != "income" && event.Payload.Type != "refund" &&
		event.Payload.Type != "debt_payment" && event.Payload.Type != "transfer" {
		return invalid(errors.New("unsupported transaction type"))
	}
	if event.Payload.Currency != "RUB" || !canonicalAmountPattern.MatchString(event.Payload.Amount) {
		return invalid(errors.New("invalid transaction money"))
	}
	if event.Payload.Status != "posted" && event.Payload.Status != "voided" {
		return invalid(errors.New("unsupported transaction status"))
	}
	if event.EventType == "transaction.voided" && event.Payload.Status != "voided" {
		return invalid(errors.New("voided event must contain voided state"))
	}
	occurredAt, err := time.Parse(time.RFC3339Nano, event.Payload.FinancialOccurredAt)
	if err != nil {
		return invalid(errors.New("invalid financial_occurred_at"))
	}
	recordedAt, err := time.Parse(time.RFC3339Nano, event.RecordedAt)
	if err != nil {
		return invalid(errors.New("invalid recorded_at"))
	}
	if _, err := time.Parse(time.RFC3339Nano, event.OccurredAt); err != nil {
		return invalid(errors.New("invalid occurred_at"))
	}
	return Transaction{
		TenantID: strings.ToLower(event.TenantID), OwnerID: strings.ToLower(event.Payload.OwnerID),
		ID: strings.ToLower(event.AggregateID), EventID: strings.ToLower(event.EventID),
		Type: event.Payload.Type, Amount: event.Payload.Amount, Currency: event.Payload.Currency,
		CategoryCode: event.Payload.CategoryCode, Description: event.Payload.Description, Status: event.Payload.Status,
		AggregateVersion: event.AggregateVersion, OccurredAt: occurredAt, RecordedAt: recordedAt,
	}, nil
}
