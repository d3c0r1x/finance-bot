package prices

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestClickHouseStoreWritesScopedPricePointsAsAuthenticatedJSONEachRow(t *testing.T) {
	merchant := "Market"
	point := PricePoint{
		TenantID: "00000000-0000-4000-8000-000000000002", OwnerID: "00000000-0000-4000-8000-000000000004",
		ReceiptID: "00000000-0000-4000-8000-000000000003", TransactionID: "00000000-0000-4000-8000-000000000005",
		ItemID: "00000000-0000-4000-8000-000000000006", EventID: "00000000-0000-4000-8000-000000000001",
		AggregateVersion: 2, ReceiptDate: "2026-10-01", PurchasedAt: time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC),
		RecordedAt: time.Date(2026, 10, 1, 9, 0, 1, 0, time.UTC), Merchant: &merchant,
		Name: "Tea 500g", Quantity: "2.000000", LineSum: "50.00", UnitPrice: "25.000000",
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, password, ok := r.BasicAuth(); !ok || user != "analytics_writer" || password != "test-secret" {
			t.Errorf("BasicAuth() = %q/%q/%v, want configured writer credentials", user, password, ok)
		}
		if got := r.URL.Query().Get("database"); got != "finance_analytics" {
			t.Errorf("database query parameter = %q, want finance_analytics", got)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("ReadAll() error = %v", err)
		}
		if !strings.HasPrefix(string(body), "INSERT INTO receipt_price_items FORMAT JSONEachRow\n") {
			t.Errorf("insert body = %q, missing fixed table/format statement", body)
		}
		var stored map[string]any
		if err := json.Unmarshal(bytesTrimQuery(body), &stored); err != nil {
			t.Errorf("insert JSONEachRow is invalid: %v", err)
		} else {
			for key, want := range map[string]any{
				"tenant_id": point.TenantID, "owner_user_id": point.OwnerID, "receipt_id": point.ReceiptID,
				"event_id": point.EventID, "aggregate_version": float64(2), "item_id": point.ItemID,
				"quantity": point.Quantity, "line_sum": point.LineSum, "unit_price": point.UnitPrice,
			} {
				if stored[key] != want {
					t.Errorf("stored[%q] = %v, want %v", key, stored[key], want)
				}
			}
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	store, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{
		Endpoint: server.URL, Database: "finance_analytics", Username: "analytics_writer", Password: "test-secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := store.InsertPricePoints(context.Background(), []PricePoint{point}); err != nil {
		t.Fatal(err)
	}
}

func TestClickHouseStoreReadsDeduplicatedHistoryWithTenantAndOwnerFilters(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, _, ok := r.BasicAuth(); !ok || user != "analytics_reader" {
			t.Errorf("BasicAuth() user = %q, ok=%v, want analytics_reader", user, ok)
		}
		if got := r.URL.Query().Get("param_tenant_id"); got != "00000000-0000-4000-8000-000000000002" {
			t.Errorf("param_tenant_id = %q", got)
		}
		if got := r.URL.Query().Get("param_owner_user_id"); got != "00000000-0000-4000-8000-000000000004" {
			t.Errorf("param_owner_user_id = %q", got)
		}
		if got := r.URL.Query().Get("param_limit"); got != "100" {
			t.Errorf("param_limit = %q, want 100", got)
		}
		if got := r.URL.Query().Get("param_before"); got != "2026-10-02 00:00:00.000000" {
			t.Errorf("param_before = %q, want UTC cutoff", got)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("ReadAll() error = %v", err)
		}
		query := string(body)
		for _, required := range []string{
			"FROM receipt_price_items FINAL", "tenant_id = {tenant_id:UUID}",
			"owner_user_id = {owner_user_id:UUID}",
			"receipt_price_items.purchased_at < {before:DateTime64(6, 'UTC')}",
			"LIMIT {limit:UInt16}", "FORMAT JSONEachRow",
		} {
			if !strings.Contains(query, required) {
				t.Errorf("history query %q missing %q", query, required)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"tenant_id":"00000000-0000-4000-8000-000000000002","owner_user_id":"00000000-0000-4000-8000-000000000004","receipt_id":"00000000-0000-4000-8000-000000000003","transaction_id":"00000000-0000-4000-8000-000000000005","item_id":"00000000-0000-4000-8000-000000000006","event_id":"00000000-0000-4000-8000-000000000001","aggregate_version":2,"receipt_date":"2026-10-01","purchased_at":"2026-10-01 09:00:00.000000","recorded_at":"2026-10-01 09:00:01.000000","merchant":"Market","name":"Tea 500g","quantity":"2.000000","line_sum":"50.00","unit_price":"25.000000"}`+"\n")
	}))
	defer server.Close()
	store, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{
		Endpoint: server.URL, Database: "finance_analytics", Username: "analytics_reader", Password: "test-secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	before := time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC)
	points, err := store.History(context.Background(), "00000000-0000-4000-8000-000000000002",
		"00000000-0000-4000-8000-000000000004", before, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(points) != 1 || points[0].UnitPrice != "25.000000" || points[0].OwnerID != "00000000-0000-4000-8000-000000000004" {
		t.Fatalf("History() = %+v, want one owner-scoped point", points)
	}
	if !points[0].PurchasedAt.Equal(time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC)) {
		t.Fatalf("PurchasedAt = %s, want UTC event time", points[0].PurchasedAt)
	}
}

func TestClickHouseStoreRejectsPlaintextRemoteAndOutOfScopeRows(t *testing.T) {
	if _, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{
		Endpoint: "http://analytics.internal:8123", Database: "finance_analytics",
		Username: "analytics_reader", Password: "secret",
	}); err == nil {
		t.Fatal("remote ClickHouse accepted plaintext HTTP")
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"tenant_id":"00000000-0000-4000-8000-000000000002","owner_user_id":"00000000-0000-4000-8000-000000000099","receipt_id":"00000000-0000-4000-8000-000000000003","transaction_id":"00000000-0000-4000-8000-000000000005","item_id":"00000000-0000-4000-8000-000000000006","event_id":"00000000-0000-4000-8000-000000000001","aggregate_version":2,"receipt_date":"2026-10-01","purchased_at":"2026-10-01 09:00:00.000000","recorded_at":"2026-10-01 09:00:01.000000","merchant":"Market","name":"Tea 500g","quantity":"2.000000","line_sum":"50.00","unit_price":"25.000000"}`+"\n")
	}))
	defer server.Close()
	store, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{
		Endpoint: server.URL, Database: "finance_analytics", Username: "analytics_reader", Password: "secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.History(context.Background(), "00000000-0000-4000-8000-000000000002",
		"00000000-0000-4000-8000-000000000004", time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC), 100)
	if err == nil {
		t.Fatal("ClickHouse row outside requested owner scope was returned")
	}
}

func TestClickHouseStorePingChecksTheConfiguredDatabase(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Query().Get("database"); got != "finance_analytics" {
			t.Errorf("database = %q, want finance_analytics", got)
		}
		body, _ := io.ReadAll(r.Body)
		if strings.TrimSpace(string(body)) != "SELECT 1 FORMAT TabSeparated" {
			t.Errorf("Ping query = %q", body)
		}
		_, _ = io.WriteString(w, "1\n")
	}))
	defer server.Close()
	store, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{
		Endpoint: server.URL, Database: "finance_analytics", Username: "analytics_reader", Password: "secret",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Ping(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func bytesTrimQuery(body []byte) []byte {
	return bytes.TrimSpace(body[len("INSERT INTO receipt_price_items FORMAT JSONEachRow"):])
}
