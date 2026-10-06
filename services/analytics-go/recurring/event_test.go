package recurring

import (
	"errors"
	"strings"
	"testing"
	"time"
)

const transactionStateEvent = `{"event_id":"00000000-0000-4000-8000-000000000201","event_type":"transaction.created","schema_version":1,"tenant_id":"00000000-0000-4000-8000-000000000101","aggregate_type":"transaction","aggregate_id":"00000000-0000-4000-8000-000000000102","aggregate_version":1,"occurred_at":"2026-08-01T12:00:00Z","recorded_at":"2026-08-01T12:00:00Z","producer":"core","correlation_id":"test","payload":{"owner_user_id":"00000000-0000-4000-8000-000000000103","type":"expense","amount":"100.00","currency":"RUB","category_code":"services","description":"Video plan","source":"manual","status":"posted","financial_occurred_at":"2026-08-01T09:30:00+03:00"}}`

func TestProjectTransactionStateEventPreservesFullStateAndFinancialDate(t *testing.T) {
	transaction, err := ProjectTransactionStateEvent([]byte(transactionStateEvent))
	if err != nil {
		t.Fatal(err)
	}
	if transaction.ID != "00000000-0000-4000-8000-000000000102" || transaction.OwnerID != "00000000-0000-4000-8000-000000000103" ||
		transaction.Description != "Video plan" || transaction.AggregateVersion != 1 || transaction.Status != "posted" ||
		transaction.OccurredAt.Format(time.RFC3339) != "2026-08-01T09:30:00+03:00" {
		t.Fatalf("projected transaction = %+v", transaction)
	}
}

func TestProjectTransactionStateEventAcceptsVoidedSnapshotAndRejectsMalformedContract(t *testing.T) {
	voided := []byte(`{"event_id":"00000000-0000-4000-8000-000000000201","event_type":"transaction.voided","schema_version":1,"tenant_id":"00000000-0000-4000-8000-000000000101","aggregate_type":"transaction","aggregate_id":"00000000-0000-4000-8000-000000000102","aggregate_version":2,"occurred_at":"2026-08-02T12:00:00Z","recorded_at":"2026-08-02T12:00:00Z","producer":"core","payload":{"owner_user_id":"00000000-0000-4000-8000-000000000103","type":"expense","amount":"100.00","currency":"RUB","category_code":"services","description":"Video plan","source":"manual","status":"voided","financial_occurred_at":"2026-08-01T09:30:00+03:00"}}`)
	transaction, err := ProjectTransactionStateEvent(voided)
	if err != nil || transaction.Status != "voided" || transaction.AggregateVersion != 2 {
		t.Fatalf("voided snapshot = %+v, err=%v", transaction, err)
	}
	invalidCases := [][]byte{
		[]byte(`{}`),
		[]byte(strings.Replace(transactionStateEvent, `"amount":"100.00"`, `"amount":"1e2"`, 1)),
		[]byte(strings.TrimSuffix(transactionStateEvent, "}") + `,"uncontracted":true}`),
		[]byte(transactionStateEvent + ` {}`),
	}
	for _, raw := range invalidCases {
		_, err := ProjectTransactionStateEvent(raw)
		var invalid *InvalidEventError
		if !errors.As(err, &invalid) {
			t.Fatalf("invalid event %s returned %v, want InvalidEventError", raw, err)
		}
	}
}
