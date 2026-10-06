package advice

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestF45ProgressHandlerRequiresServiceTokenAndReturnsCalculation(t *testing.T) {
	handler, err := NewF45Handler("f45-test-secret")
	if err != nil {
		t.Fatal(err)
	}
	body := `{"inputWatermark":"9","asOf":"2026-10-20T12:00:00Z","goal":{"key":"chips","scope":"product","unit":"count","acceptedAt":"2026-10-01T12:00:00Z","endsAt":"2026-10-31T12:00:00Z","countTarget":1},"purchases":[{"productKey":"chips","lineSum":"10.00","purchasedAt":"2026-10-02T12:00:00Z"}]}`
	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/progress", strings.NewReader(body)))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("missing internal token must be rejected, got %d", unauthorized.Code)
	}
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/progress", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer f45-test-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("valid progress request failed with %d: %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), `"algorithmVersion":"goal-progress-f45.v1"`) ||
		!strings.Contains(response.Body.String(), `"bought":1`) || !strings.Contains(response.Body.String(), `"met":true`) {
		t.Fatalf("handler returned an incomplete progress DTO: %s", response.Body.String())
	}
}

func TestF45ProgressHandlerRejectsUnknownFieldsAndTrailingJSON(t *testing.T) {
	handler, err := NewF45Handler("f45-test-secret")
	if err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{
		`{"inputWatermark":"1","asOf":"2026-10-20T12:00:00Z","goal":{},"purchases":[],"tenantId":"other"}`,
		`{"inputWatermark":"1","asOf":"2026-10-20T12:00:00Z","goal":{},"purchases":[]} {}`,
	} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/progress", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer f45-test-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("malformed request must be rejected, got %d for %s", response.Code, body)
		}
	}
}
