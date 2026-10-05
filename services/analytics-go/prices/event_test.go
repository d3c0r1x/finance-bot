package prices

import (
	"strings"
	"testing"
	"time"
)

const confirmedReceiptEvent = `{"event_id":"00000000-0000-4000-8000-000000000001","event_type":"receipt.confirmed","schema_version":1,"tenant_id":"00000000-0000-4000-8000-000000000002","aggregate_type":"receipt","aggregate_id":"00000000-0000-4000-8000-000000000003","aggregate_version":2,"occurred_at":"2026-10-01T09:00:00Z","recorded_at":"2026-10-01T09:00:01Z","producer":"core","payload":{"owner_user_id":"00000000-0000-4000-8000-000000000004","transaction_id":"00000000-0000-4000-8000-000000000005","receipt_date":"2026-10-01","currency":"RUB","merchant":"Market","items":[{"item_id":"00000000-0000-4000-8000-000000000006","name":"Tea 500g","quantity":"2.000000","line_sum":"50.00"},{"item_id":"00000000-0000-4000-8000-000000000007","name":"Unknown quantity","quantity":null,"line_sum":"3.00"}]}}`

func TestProjectConfirmedReceiptEventMapsAllScopedValidPriceLines(t *testing.T) {
	points, err := ProjectConfirmedReceiptEvent([]byte(confirmedReceiptEvent))
	if err != nil {
		t.Fatal(err)
	}
	if len(points) != 1 {
		t.Fatalf("projected %d price points, want only the one with a valid quantity", len(points))
	}
	got := points[0]
	if got.TenantID != "00000000-0000-4000-8000-000000000002" ||
		got.OwnerID != "00000000-0000-4000-8000-000000000004" ||
		got.ReceiptID != "00000000-0000-4000-8000-000000000003" ||
		got.ItemID != "00000000-0000-4000-8000-000000000006" ||
		got.EventID != "00000000-0000-4000-8000-000000000001" ||
		got.AggregateVersion != 2 || got.Name != "Tea 500g" || got.UnitPrice != "25.000000" {
		t.Fatalf("projected price point = %+v, missing immutable event scope or unit price", got)
	}
	if got.Merchant == nil || *got.Merchant != "Market" {
		t.Fatalf("Merchant = %v, want Market", got.Merchant)
	}
	if !got.PurchasedAt.Equal(time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC)) {
		t.Fatalf("PurchasedAt = %s, want event financial time", got.PurchasedAt)
	}
}

func TestProjectConfirmedReceiptEventRejectsWrongEventAndMalformedEnvelope(t *testing.T) {
	wrongType := strings.Replace(confirmedReceiptEvent, `"receipt.confirmed"`, `"receipt.draft"`, 1)
	if _, err := ProjectConfirmedReceiptEvent([]byte(wrongType)); err == nil {
		t.Fatal("non-confirmation event was accepted as a confirmed receipt")
	}
	if _, err := ProjectConfirmedReceiptEvent([]byte(confirmedReceiptEvent + ` {}`)); err == nil {
		t.Fatal("trailing JSON document was accepted")
	}
}
