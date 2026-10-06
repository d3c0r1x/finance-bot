package advice

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestF43CoreClientClaimsAndCompletesWithServiceToken(t *testing.T) {
	var claimSeen, resultSeen bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Analytics-Service-Token") != "secret" {
			t.Errorf("service token missing")
		}
		switch r.URL.Path {
		case "/internal/v1/analytics/advice-jobs/claim":
			claimSeen = true
			if r.Method != http.MethodPost {
				t.Errorf("claim method = %s", r.Method)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"id":"job","tenantId":"00000000-0000-0000-0000-000000000001","ownerUserId":"00000000-0000-0000-0000-000000000002","inputWatermark":"1","algorithmVersion":"advice-f43.v1","leaseToken":"00000000-0000-0000-0000-000000000003","attemptCount":1,"input":{"inputWatermark":"1","asOf":"2026-01-01T00:00:00Z","timeZone":"UTC","items":[],"recalculations":[]}}`))
		case "/internal/v1/analytics/advice-jobs/job/result":
			resultSeen = true
			var body F43JobResult
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Errorf("decode result: %v", err)
			}
			if body.Report == nil || body.Report.InputWatermark != "1" || body.LeaseToken == "" {
				t.Errorf("bad result: %+v", body)
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	client, err := NewF43CoreClient(server.URL, "secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	job, err := client.Claim(context.Background())
	if err != nil || job == nil {
		t.Fatalf("Claim() = (%+v, %v)", job, err)
	}
	report, err := BuildF43Report(job.Input)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Complete(context.Background(), job.ID, F43JobResult{LeaseToken: job.LeaseToken,
		InputWatermark: job.InputWatermark, AlgorithmVersion: job.AlgorithmVersion, Report: &report}); err != nil {
		t.Fatal(err)
	}
	if !claimSeen || !resultSeen {
		t.Fatalf("claim seen=%v result seen=%v", claimSeen, resultSeen)
	}
}

func TestF43CoreClientNoJobAndTransientStatus(t *testing.T) {
	status := http.StatusNoContent
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(status) }))
	defer server.Close()
	client, err := NewF43CoreClient(server.URL, "secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	job, err := client.Claim(context.Background())
	if err != nil || job != nil {
		t.Fatalf("empty Claim() = (%+v, %v)", job, err)
	}
	status = http.StatusServiceUnavailable
	if _, err := client.Claim(context.Background()); err == nil {
		t.Fatal("expected 503 to be an error")
	}
}

func TestF43CoreClientRequiresConfigAndRejectsUnknownClaimFields(t *testing.T) {
	if _, err := NewF43CoreClient("", "secret", time.Second); err == nil {
		t.Fatal("expected missing URL error")
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"id":"job","unexpected":true}`))
	}))
	defer server.Close()
	client, err := NewF43CoreClient(server.URL, "secret", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Claim(context.Background()); err == nil {
		t.Fatal("expected strict response decoding")
	}
}
