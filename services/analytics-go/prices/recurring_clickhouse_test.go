package prices

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/recurring"
)

func TestClickHouseStoreWritesTransactionFullStateIdempotently(t *testing.T) {
	transaction := recurring.Transaction{
		TenantID: "00000000-0000-4000-8000-000000000002", OwnerID: "00000000-0000-4000-8000-000000000004",
		ID: "00000000-0000-4000-8000-000000000003", EventID: "00000000-0000-4000-8000-000000000001",
		AggregateVersion: 3, Type: "expense", Amount: "99.90", Currency: "RUB", CategoryCode: "services",
		Description: "Video plan", Status: "posted", OccurredAt: time.Date(2026, 10, 1, 9, 0, 0, 0, time.UTC),
		RecordedAt: time.Date(2026, 10, 1, 9, 0, 1, 0, time.UTC),
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, password, ok := r.BasicAuth(); !ok || user != "analytics_writer" || password != "test-secret" {
			t.Errorf("BasicAuth() = %q/%q/%v", user, password, ok)
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read body: %v", err)
		}
		if !strings.HasPrefix(string(body), "INSERT INTO transaction_states FORMAT JSONEachRow\n") {
			t.Errorf("insert query = %q", body)
		}
		var row map[string]any
		if err := json.Unmarshal([]byte(strings.TrimSpace(string(body)[len("INSERT INTO transaction_states FORMAT JSONEachRow"):])), &row); err != nil {
			t.Errorf("invalid JSONEachRow: %v", err)
		}
		for key, expected := range map[string]any{
			"tenant_id": transaction.TenantID, "owner_user_id": transaction.OwnerID,
			"transaction_id": transaction.ID, "event_id": transaction.EventID,
			"aggregate_version": float64(3), "amount": transaction.Amount, "status": transaction.Status,
		} {
			if row[key] != expected {
				t.Errorf("row[%q]=%v want %v", key, row[key], expected)
			}
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	store, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{Endpoint: server.URL, Database: "finance_analytics", Username: "analytics_writer", Password: "test-secret"})
	if err != nil {
		t.Fatal(err)
	}
	if err := store.InsertTransactions(context.Background(), []recurring.Transaction{transaction}); err != nil {
		t.Fatal(err)
	}
}

func TestClickHouseStoreReadsDeduplicatedMemberHistory(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("param_tenant_id") != "00000000-0000-4000-8000-000000000002" ||
			r.URL.Query().Get("param_owner_user_id") != "00000000-0000-4000-8000-000000000004" ||
			r.URL.Query().Get("param_limit") != "200" {
			t.Errorf("scope/limit query = %v", r.URL.Query())
		}
		body, _ := io.ReadAll(r.Body)
		for _, required := range []string{"FROM transaction_states FINAL", "tenant_id = {tenant_id:UUID}",
			"owner_user_id = {owner_user_id:UUID}", "ORDER BY occurred_at DESC", "LIMIT {limit:UInt16}", "FORMAT JSONEachRow"} {
			if !strings.Contains(string(body), required) {
				t.Errorf("query missing %q", required)
			}
		}
		_, _ = io.WriteString(w, `{"tenant_id":"00000000-0000-4000-8000-000000000002","owner_user_id":"00000000-0000-4000-8000-000000000004","transaction_id":"00000000-0000-4000-8000-000000000003","event_id":"00000000-0000-4000-8000-000000000001","aggregate_version":3,"type":"expense","amount":"99.90","currency":"RUB","category_code":"services","description":"Video plan","status":"posted","occurred_at":"2026-10-01 09:00:00.000000","recorded_at":"2026-10-01 09:00:01.000000"}`+"\n")
	}))
	defer server.Close()
	store, err := NewClickHouseHTTPStore(ClickHouseHTTPConfig{Endpoint: server.URL, Database: "finance_analytics", Username: "analytics_reader", Password: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	transactions, err := store.Transactions(context.Background(), "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000004", 200)
	if err != nil {
		t.Fatal(err)
	}
	if len(transactions) != 1 || transactions[0].Description != "Video plan" || transactions[0].Amount != "99.90" || transactions[0].AggregateVersion != 3 {
		t.Fatalf("Transactions() = %+v", transactions)
	}
}
