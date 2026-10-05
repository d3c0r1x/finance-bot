package prices

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

type fakePriceHistoryReader struct {
	tenantID string
	ownerID  string
	before   time.Time
	limit    int
	points   []PricePoint
	err      error
	calls    int
}

func (reader *fakePriceHistoryReader) History(_ context.Context, tenantID, ownerID string,
	before time.Time, limit int) ([]PricePoint, error) {
	reader.calls++
	reader.tenantID, reader.ownerID, reader.before, reader.limit = tenantID, ownerID, before, limit
	return reader.points, reader.err
}

func TestPriceHistoryHandlerReturnsOwnerScopedMedianAndPriceSignal(t *testing.T) {
	reader := &fakePriceHistoryReader{points: []PricePoint{
		pricePoint("00000000-0000-4000-8000-000000000010", "00000000-0000-4000-8000-000000000011", "80.00", "1", "2026-10-01T09:00:00Z"),
		pricePoint("00000000-0000-4000-8000-000000000012", "00000000-0000-4000-8000-000000000013", "120.00", "1", "2026-10-05T09:00:00Z"),
	}}
	handler, err := NewPriceHistoryHandler("test-core-service-token-123456", reader)
	if err != nil {
		t.Fatal(err)
	}
	body := `{"tenantId":"00000000-0000-4000-8000-000000000002","ownerUserId":"00000000-0000-4000-8000-000000000004","receiptId":"00000000-0000-4000-8000-000000000020","itemId":"00000000-0000-4000-8000-000000000021","name":"Bread 400g","quantity":"1.000000","lineSum":"130.00","currency":"RUB","purchasedAt":"2026-10-10T09:00:00Z"}`
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/prices/compare", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test-core-service-token-123456")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s, want 200", response.Code, response.Body.String())
	}
	var result PriceComparisonResponse
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if !result.HasBaseline || !result.Signal || result.Direction == nil || *result.Direction != DirectionUp ||
		result.BaselineUnitPrice == nil || *result.BaselineUnitPrice != "100.000000" ||
		result.CurrentUnitPrice != "130.000000" || result.Change == nil || *result.Change != "30.000000" ||
		result.PriorPurchases != 2 || len(result.History) != 3 {
		t.Fatalf("price comparison response = %+v, want two prior points and an up signal", result)
	}
	if reader.tenantID != "00000000-0000-4000-8000-000000000002" ||
		reader.ownerID != "00000000-0000-4000-8000-000000000004" ||
		!reader.before.Equal(time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)) || reader.limit != 5000 {
		t.Fatalf("history query scope/cutoff = %+v", reader)
	}
}

func TestPriceHistoryHandlerHidesNoHistoryAndRejectsUnauthorizedOrStorageFailure(t *testing.T) {
	reader := &fakePriceHistoryReader{}
	handler, err := NewPriceHistoryHandler("test-core-service-token-123456", reader)
	if err != nil {
		t.Fatal(err)
	}
	body := `{"tenantId":"00000000-0000-4000-8000-000000000002","ownerUserId":"00000000-0000-4000-8000-000000000004","receiptId":"00000000-0000-4000-8000-000000000020","itemId":"00000000-0000-4000-8000-000000000021","name":"Bread 400g","quantity":"1.000000","lineSum":"50.00","currency":"RUB","purchasedAt":"2026-10-10T09:00:00Z"}`
	unauthorized := httptest.NewRequest(http.MethodPost, "/internal/v1/prices/compare", strings.NewReader(body))
	unauthorizedResponse := httptest.NewRecorder()
	handler.ServeHTTP(unauthorizedResponse, unauthorized)
	if unauthorizedResponse.Code != http.StatusUnauthorized || reader.calls != 0 {
		t.Fatalf("unauthorized status/calls = %d/%d, want 401/0", unauthorizedResponse.Code, reader.calls)
	}
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/prices/compare", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer test-core-service-token-123456")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s, want 200", response.Code, response.Body.String())
	}
	var result PriceComparisonResponse
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.HasBaseline || result.BaselineUnitPrice != nil || result.Change != nil || result.Direction != nil ||
		result.CurrentUnitPrice != "50.000000" || len(result.History) != 1 {
		t.Fatalf("no-history response = %+v, must use null baseline and current-only history", result)
	}
	reader.err = errors.New("private ClickHouse detail")
	failedRequest := httptest.NewRequest(http.MethodPost, "/internal/v1/prices/compare", strings.NewReader(body))
	failedRequest.Header.Set("Authorization", "Bearer test-core-service-token-123456")
	failedResponse := httptest.NewRecorder()
	handler.ServeHTTP(failedResponse, failedRequest)
	if failedResponse.Code != http.StatusServiceUnavailable || strings.Contains(failedResponse.Body.String(), "private ClickHouse detail") {
		t.Fatalf("storage failure response = %d %q, internal detail must not escape", failedResponse.Code, failedResponse.Body.String())
	}
}

func pricePoint(receiptID, itemID, lineSum, quantity, purchasedAt string) PricePoint {
	purchased, err := time.Parse(time.RFC3339, purchasedAt)
	if err != nil {
		panic(err)
	}
	merchant := "Market"
	unitPrice, err := UnitPrice(lineSum, quantity)
	if err != nil {
		panic(err)
	}
	return PricePoint{
		TenantID: "00000000-0000-4000-8000-000000000002", OwnerID: "00000000-0000-4000-8000-000000000004",
		ReceiptID: receiptID, TransactionID: "00000000-0000-4000-8000-000000000030", ItemID: itemID,
		EventID: "00000000-0000-4000-8000-000000000031", AggregateVersion: 2,
		ReceiptDate: purchased.Format(time.DateOnly), PurchasedAt: purchased, RecordedAt: purchased,
		Merchant: &merchant, Name: "Bread 400g", Quantity: quantity, LineSum: lineSum, UnitPrice: unitPrice,
	}
}
