package prices

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestShoppingCandidatesHandlerReadsOnlyRequestedMemberHistory(t *testing.T) {
	now := time.Now().UTC()
	reader := &fakeShoppingHistoryReader{points: []PricePoint{
		shoppingAPIPoint(1, "Milk Fresh 1l", now.AddDate(0, 0, -29), "90.00"),
		shoppingAPIPoint(2, "Milk Fresh 1l", now.AddDate(0, 0, -19), "100.00"),
		shoppingAPIPoint(3, "Milk Fresh 1l", now.AddDate(0, 0, -9), "110.00"),
	}}
	handler, err := NewShoppingCandidatesHandler("shopping-service-token", reader)
	if err != nil {
		t.Fatal(err)
	}
	request := shoppingAPIRequest(`{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002"}`)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("shopping candidates returned %d: %s", response.Code, response.Body.String())
	}
	var list ShoppingList
	if err := json.Unmarshal(response.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	if reader.tenantID != "00000000-0000-4000-8000-000000000001" ||
		reader.ownerID != "00000000-0000-4000-8000-000000000002" || reader.limit != priceHistoryLimit {
		t.Fatalf("history scope = %s/%s limit=%d", reader.tenantID, reader.ownerID, reader.limit)
	}
	if len(list.Candidates) != 1 || list.Candidates[0].PurchaseCount != 3 ||
		list.Candidates[0].DaysUntilDue != 0 || list.EstimatedListCost != "100.00" || list.InventoryTracked {
		t.Fatalf("shopping response = %+v, want one real due suggestion and no inventory tracking", list)
	}
}

func TestShoppingCandidatesHandlerRejectsUnauthorizedAndOutOfScopeHistory(t *testing.T) {
	point := shoppingAPIPoint(1, "Milk Fresh 1l", time.Now().UTC().AddDate(0, 0, -5), "90.00")
	reader := &fakeShoppingHistoryReader{points: []PricePoint{point}}
	handler, err := NewShoppingCandidatesHandler("shopping-service-token", reader)
	if err != nil {
		t.Fatal(err)
	}
	request := shoppingAPIRequest(`{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002"}`)
	request.Header.Del("Authorization")
	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, request)
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("missing service token returned %d, want 401", unauthorized.Code)
	}

	point.OwnerID = "00000000-0000-4000-8000-000000000099"
	reader.points = []PricePoint{point}
	request = shoppingAPIRequest(`{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002"}`)
	outOfScope := httptest.NewRecorder()
	handler.ServeHTTP(outOfScope, request)
	if outOfScope.Code != http.StatusServiceUnavailable {
		t.Fatalf("out-of-scope history returned %d, want 503", outOfScope.Code)
	}
}

func TestShoppingCandidatesHandlerRejectsMalformedRequest(t *testing.T) {
	handler, err := NewShoppingCandidatesHandler("shopping-service-token", &fakeShoppingHistoryReader{})
	if err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{
		`{"tenantId":"bad","ownerUserId":"00000000-0000-4000-8000-000000000002"}`,
		`{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002","extra":true}`,
	} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, shoppingAPIRequest(body))
		if response.Code != http.StatusBadRequest {
			t.Errorf("malformed request returned %d, want 400: %s", response.Code, response.Body.String())
		}
	}
}

type fakeShoppingHistoryReader struct {
	points   []PricePoint
	tenantID string
	ownerID  string
	before   time.Time
	limit    int
	err      error
}

func (reader *fakeShoppingHistoryReader) History(_ context.Context, tenantID, ownerID string,
	before time.Time, limit int) ([]PricePoint, error) {
	reader.tenantID, reader.ownerID, reader.before, reader.limit = tenantID, ownerID, before, limit
	return append([]PricePoint(nil), reader.points...), reader.err
}

func shoppingAPIRequest(body string) *http.Request {
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/shopping/candidates", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer shopping-service-token")
	return request
}

func shoppingAPIPoint(id int, name string, purchased time.Time, price string) PricePoint {
	point := catalogPoint(id, name, purchased.UTC().Format(time.DateOnly), price, "Market A")
	point.PurchasedAt = utcDay(purchased).Add(time.Hour)
	point.RecordedAt = point.PurchasedAt.Add(time.Hour)
	point.ReceiptDate = point.PurchasedAt.Format(time.DateOnly)
	return point
}
