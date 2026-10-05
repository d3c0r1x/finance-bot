package prices

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const inflationServiceToken = "test-core-service-token-123456"

func TestPersonalInflationHandlerReturnsOnlyRequestedMemberBasket(t *testing.T) {
	reader := &fakePriceHistoryReader{points: []PricePoint{
		inflationPoint(1, 1, "Milk Whole 1l", "100.00", "2026-06-01T09:00:00Z"),
		inflationPoint(2, 2, "Milk Whole 1l", "120.00", "2026-06-20T09:00:00Z"),
		inflationPoint(3, 3, "Milk Whole 1l", "132.00", "2026-08-01T09:00:00Z"),
		inflationPoint(4, 4, "Bread Rye 400g", "40.00", "2026-06-01T09:00:00Z"),
		inflationPoint(5, 5, "Bread Rye 400g", "50.00", "2026-06-20T09:00:00Z"),
		inflationPoint(6, 6, "Bread Rye 400g", "45.00", "2026-08-01T09:00:00Z"),
		inflationPoint(7, 7, "Coffee Arabica 250g", "1000.00", "2026-06-01T09:00:00Z"),
		inflationPoint(8, 8, "Coffee Arabica 250g", "1200.00", "2026-06-20T09:00:00Z"),
		inflationPoint(9, 9, "Coffee Arabica 250g", "990.00", "2026-08-01T09:00:00Z"),
	}}
	handler, err := NewPersonalInflationHandler(inflationServiceToken, reader)
	if err != nil {
		t.Fatal(err)
	}
	response := serveInflationRequest(handler, http.MethodPost, personalInflationBody, inflationServiceToken)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s, want 200", response.Code, response.Body.String())
	}
	var result PersonalInflation
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if !result.Available || result.ProductCount != 3 || result.BasketBefore == nil || *result.BasketBefore != "2510.00" {
		t.Fatalf("personal inflation response = %+v, want three-product member basket", result)
	}
	asOf := time.Date(2026, 10, 6, 12, 0, 0, 0, time.UTC)
	if reader.tenantID != inflationTenantID || reader.ownerID != inflationOwnerID ||
		!reader.before.Equal(asOf.Add(time.Microsecond)) || reader.limit != inflationHistoryLimit {
		t.Fatalf("history query scope/cutoff = %+v", reader)
	}
}

func TestPersonalInflationHandlerRejectsUnauthorizedAndDoesNotLeakStorageErrors(t *testing.T) {
	reader := &fakePriceHistoryReader{}
	handler, err := NewPersonalInflationHandler(inflationServiceToken, reader)
	if err != nil {
		t.Fatal(err)
	}
	unauthorized := serveInflationRequest(handler, http.MethodPost, personalInflationBody, "")
	if unauthorized.Code != http.StatusUnauthorized || reader.calls != 0 {
		t.Fatalf("unauthorized status/calls = %d/%d, want 401/0", unauthorized.Code, reader.calls)
	}
	reader.err = errors.New("private ClickHouse detail")
	failed := serveInflationRequest(handler, http.MethodPost, personalInflationBody, inflationServiceToken)
	if failed.Code != http.StatusServiceUnavailable || strings.Contains(failed.Body.String(), "private ClickHouse detail") {
		t.Fatalf("storage failure response = %d %q, internal error must not escape", failed.Code, failed.Body.String())
	}
}

func TestPersonalInflationHandlerRejectsInvalidRequestsAndOutOfScopeRows(t *testing.T) {
	reader := &fakePriceHistoryReader{}
	handler, err := NewPersonalInflationHandler(inflationServiceToken, reader)
	if err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{
		`{"tenantId":"bad","ownerUserId":"` + inflationOwnerID + `","asOf":"2026-10-06T12:00:00Z"}`,
		`{"tenantId":"` + inflationTenantID + `","ownerUserId":"` + inflationOwnerID + `","asOf":"invalid"}`,
		`{"tenantId":"` + inflationTenantID + `","ownerUserId":"` + inflationOwnerID + `","asOf":"2026-10-06T12:00:00Z","extra":true}`,
	} {
		response := serveInflationRequest(handler, http.MethodPost, body, inflationServiceToken)
		if response.Code != http.StatusBadRequest || reader.calls != 0 {
			t.Fatalf("invalid request status/calls = %d/%d for %s", response.Code, reader.calls, body)
		}
	}
	reader.points = []PricePoint{inflationPoint(1, 1, "Milk Whole 1l", "10.00", "2026-08-01T09:00:00Z")}
	reader.points[0].OwnerID = "00000000-0000-4000-8000-000000000099"
	response := serveInflationRequest(handler, http.MethodPost, personalInflationBody, inflationServiceToken)
	if response.Code != http.StatusServiceUnavailable || strings.Contains(response.Body.String(), "00000000") {
		t.Fatalf("out-of-scope result = %d %q, must fail closed without disclosing row", response.Code, response.Body.String())
	}
}

func serveInflationRequest(handler http.Handler, method, body, token string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, "/internal/v1/analytics/personal-inflation", strings.NewReader(body))
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

const personalInflationBody = `{"tenantId":"00000000-0000-4000-8000-000000000002","ownerUserId":"00000000-0000-4000-8000-000000000004","asOf":"2026-10-06T12:00:00Z"}`
