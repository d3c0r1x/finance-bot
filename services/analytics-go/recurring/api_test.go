package recurring

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type fakeTransactionReader struct {
	rows              []Transaction
	err               error
	tenantID, ownerID string
	limit             int
}

func (reader *fakeTransactionReader) Transactions(_ context.Context, tenantID, ownerID string, limit int) ([]Transaction, error) {
	reader.tenantID, reader.ownerID, reader.limit = tenantID, ownerID, limit
	return reader.rows, reader.err
}

func TestHandlerRequiresServiceAuthScopesReadAndReturnsVersionedProjection(t *testing.T) {
	reader := &fakeTransactionReader{rows: []Transaction{
		recurringTransaction(1, "expense", "100.00", "Mobile", "utilities", "2026-08-01T10:00:00+03:00"),
		recurringTransaction(2, "expense", "100.00", "Mobile", "utilities", "2026-08-08T10:00:00+03:00"),
		recurringTransaction(3, "expense", "100.00", "Mobile", "utilities", "2026-08-15T10:00:00+03:00"),
	}}
	handler, err := NewHandler("service-secret", reader)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/recurring",
		strings.NewReader(`{"tenantId":"`+recurringTenantID+`","ownerUserId":"`+recurringOwnerID+`","asOf":"2026-08-20T00:00:00+03:00","timeZone":"Europe/Moscow"}`))
	request.Header.Set("Authorization", "Bearer service-secret")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	if reader.tenantID != recurringTenantID || reader.ownerID != recurringOwnerID || reader.limit != historyLimit {
		t.Fatalf("reader scope = %s/%s limit=%d", reader.tenantID, reader.ownerID, reader.limit)
	}
	var projection Projection
	if err := json.Unmarshal(recorder.Body.Bytes(), &projection); err != nil {
		t.Fatal(err)
	}
	if projection.AlgorithmVersion != AlgorithmVersion || projection.TimeZone != "Europe/Moscow" ||
		len(projection.DueSoon) != 1 || projection.DueSoon[0].DaysUntil != 2 {
		t.Fatalf("projection=%+v", projection)
	}
}

func TestHandlerRejectsInvalidAuthRequestAndUnboundedHistory(t *testing.T) {
	reader := &fakeTransactionReader{}
	handler, err := NewHandler("service-secret", reader)
	if err != nil {
		t.Fatal(err)
	}
	body := `{"tenantId":"` + recurringTenantID + `","ownerUserId":"` + recurringOwnerID + `","asOf":"2026-08-20T00:00:00Z","timeZone":"UTC"}`
	for _, test := range []struct {
		name       string
		auth, body string
		want       int
	}{
		{"missing auth", "", body, http.StatusUnauthorized},
		{"invalid timezone", "Bearer service-secret", strings.Replace(body, "UTC", "Missing/Zone", 1), http.StatusBadRequest},
		{"unknown field", "Bearer service-secret", strings.TrimSuffix(body, "}") + `,"owner":"x"}`, http.StatusBadRequest},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/recurring", strings.NewReader(test.body))
			if test.auth != "" {
				request.Header.Set("Authorization", test.auth)
			}
			recorder := httptest.NewRecorder()
			handler.ServeHTTP(recorder, request)
			if recorder.Code != test.want {
				t.Fatalf("status=%d, want %d: %s", recorder.Code, test.want, recorder.Body.String())
			}
		})
	}
	reader.err = errors.New("storage offline")
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/recurring", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer service-secret")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("storage error status=%d", recorder.Code)
	}
	reader.err = nil
	reader.rows = make([]Transaction, historyLimit)
	for i := range reader.rows {
		reader.rows[i] = recurringTransaction(i+1, "expense", "1.00", "Rows", "x", time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC).Format(time.RFC3339))
	}
	request = httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/recurring", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer service-secret")
	recorder = httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("truncated history status=%d", recorder.Code)
	}
}
