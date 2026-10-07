package exports

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCoreClientUsesExportCredentialAndDecodesClaimAndSnapshotPage(t *testing.T) {
	var calls int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("X-Export-Service-Token") != "worker-secret" {
			t.Errorf("service token = %q", r.Header.Get("X-Export-Service-Token"))
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/internal/v1/exports/claim":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"id":"job","tenantId":"tenant","requesterUserId":"user","formatVersion":"csv-v1","fromDate":"2026-10-01","toDate":"2026-10-02","memberId":null,"allMembers":false,"requesterTimezone":"UTC","snapshotAt":"2026-10-03T00:00:00Z","rowCount":1,"leaseToken":"lease","leaseExpiresAt":"2026-10-03T00:02:00Z","attemptCount":1}`))
		case r.Method == http.MethodGet && r.URL.Path == "/internal/v1/exports/job/rows":
			if r.URL.Query().Get("leaseToken") != "lease" || r.URL.Query().Get("afterRowNumber") != "0" || r.URL.Query().Get("limit") != "10" {
				t.Errorf("page query = %q", r.URL.RawQuery)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"exportId":"job","formatVersion":"csv-v1","requesterTimezone":"UTC","snapshotAt":"2026-10-03T00:00:00Z","rowCount":1,"rows":[{"rowNumber":1,"transactionId":"tx","ownerUserId":"user","occurredAt":"2026-10-02T08:00:00Z","amount":"1.00","currency":"RUB","categoryCode":"food","subcategoryCode":"","description":"hello","transactionType":"expense","debtTarget":"","source":"manual","telegramId":""}],"nextAfterRowNumber":1}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewCoreClient(server.URL, "worker-secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	claim, err := client.Claim(context.Background())
	if err != nil || claim == nil || claim.ID != "job" || claim.RowCount != 1 || claim.LeaseToken != "lease" {
		t.Fatalf("Claim() = (%+v, %v)", claim, err)
	}
	page, err := client.Page(context.Background(), claim.ID, claim.LeaseToken, 0, 10)
	if err != nil || page.ExportID != "job" || len(page.Rows) != 1 || page.Rows[0].Amount != "1.00" {
		t.Fatalf("Page() = (%+v, %v)", page, err)
	}
	if calls != 2 {
		t.Fatalf("Core calls = %d, want 2", calls)
	}
}

func TestCoreClientSubmitsLeaseFencedCompleteAndFailure(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		if r.Header.Get("X-Export-Service-Token") != "worker-secret" {
			t.Errorf("service token = %q", r.Header.Get("X-Export-Service-Token"))
		}
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("decode body: %v", err)
		}
		if r.URL.Path == "/internal/v1/exports/job/complete" && (body["leaseToken"] != "lease" || body["byteCount"] != float64(3)) {
			t.Errorf("complete payload = %+v", body)
		}
		if r.URL.Path == "/internal/v1/exports/job/fail" && (body["leaseToken"] != "lease" || body["retryable"] != true) {
			t.Errorf("fail payload = %+v", body)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	client, err := NewCoreClient(server.URL, "worker-secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Complete(context.Background(), "job", CompleteRequest{LeaseToken: "lease", ObjectKey: "key", ByteCount: 3, SHA256: strings.Repeat("a", 64)}); err != nil {
		t.Fatal(err)
	}
	if err := client.Fail(context.Background(), "job", FailRequest{LeaseToken: "lease", ErrorCode: "upload_failed", Retryable: true}); err != nil {
		t.Fatal(err)
	}
	if strings.Join(paths, ",") != "/internal/v1/exports/job/complete,/internal/v1/exports/job/fail" {
		t.Fatalf("Core paths = %v", paths)
	}
}

func TestCoreClientTreatsNoWorkAndHTTPFailuresSafely(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/v1/exports/claim" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte("secret response body must not appear"))
	}))
	defer server.Close()
	client, err := NewCoreClient(server.URL, "worker-secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	claim, err := client.Claim(context.Background())
	if err != nil || claim != nil {
		t.Fatalf("empty claim = (%+v, %v)", claim, err)
	}
	if err := client.Complete(context.Background(), "job", CompleteRequest{LeaseToken: "lease"}); err == nil || strings.Contains(err.Error(), "secret response") {
		t.Fatalf("unsafe HTTP error = %v", err)
	}
}
