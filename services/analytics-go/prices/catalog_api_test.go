package prices

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestProductCatalogHandlerUsesMemberScopedSearchAndCatalogThresholds(t *testing.T) {
	reader := &fakeProductCatalogReader{points: []PricePoint{
		catalogPoint(1, "Tea Green 500g", "2026-09-01", "100.00", "Market A"),
		catalogPoint(2, "Tea Green 500g", "2026-09-10", "120.00", "Market B"),
		catalogPoint(3, "Tea Green 500g", "2026-09-20", "160.00", "Market C"),
	}}
	handler, err := NewProductCatalogHandler("catalog-service-token", reader)
	if err != nil {
		t.Fatal(err)
	}
	request := `{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002","query":"tea"}`
	response := serveCatalogRequest(handler, request)
	if response.Code != http.StatusOK {
		t.Fatalf("search returned status %d: %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), `"mode":"search"`) ||
		!strings.Contains(response.Body.String(), `"purchaseCount":3`) ||
		!strings.Contains(response.Body.String(), `"cheapestMerchant":"Market A"`) {
		t.Fatalf("search response lacks purchase summary: %s", response.Body.String())
	}
	if reader.tenantID != "00000000-0000-4000-8000-000000000001" ||
		reader.ownerID != "00000000-0000-4000-8000-000000000002" || reader.limit != priceHistoryLimit {
		t.Fatalf("reader scope = %s/%s limit=%d; request scope was not enforced", reader.tenantID, reader.ownerID, reader.limit)
	}
	request = `{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002","query":""}`
	response = serveCatalogRequest(handler, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"mode":"catalog"`) {
		t.Fatalf("catalog response status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestProductCatalogHandlerRejectsWrongScopeAndMalformedRequests(t *testing.T) {
	point := catalogPoint(1, "Tea Green 500g", "2026-09-01", "100.00", "Market A")
	reader := &fakeProductCatalogReader{points: []PricePoint{point}}
	handler, err := NewProductCatalogHandler("catalog-service-token", reader)
	if err != nil {
		t.Fatal(err)
	}
	request := `{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002","query":"tea"}`
	unauthorized := httptest.NewRequest(http.MethodPost, "/internal/v1/products/catalog", strings.NewReader(request))
	unauthorizedResponse := httptest.NewRecorder()
	handler.ServeHTTP(unauthorizedResponse, unauthorized)
	if unauthorizedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("missing service token returned %d, want 401", unauthorizedResponse.Code)
	}
	badScope := point
	badScope.OwnerID = "00000000-0000-4000-8000-000000000099"
	reader.points = []PricePoint{badScope}
	response := serveCatalogRequest(handler, request)
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("out-of-scope history returned %d, want 503", response.Code)
	}
	for _, invalid := range []string{
		`{"tenantId":"bad","ownerUserId":"00000000-0000-4000-8000-000000000002","query":"tea"}`,
		`{"tenantId":"00000000-0000-4000-8000-000000000001","ownerUserId":"00000000-0000-4000-8000-000000000002","query":"tea","extra":true}`,
	} {
		response = serveCatalogRequest(handler, invalid)
		if response.Code != http.StatusBadRequest {
			t.Errorf("invalid request returned %d, want 400: %s", response.Code, response.Body.String())
		}
	}
}

type fakeProductCatalogReader struct {
	points   []PricePoint
	tenantID string
	ownerID  string
	before   time.Time
	limit    int
	err      error
}

func (reader *fakeProductCatalogReader) History(_ context.Context, tenantID, ownerID string,
	before time.Time, limit int) ([]PricePoint, error) {
	reader.tenantID, reader.ownerID, reader.before, reader.limit = tenantID, ownerID, before, limit
	return append([]PricePoint(nil), reader.points...), reader.err
}

func serveCatalogRequest(handler http.Handler, body string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/products/catalog", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer catalog-service-token")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func TestProductCatalogHandlerSearchUsesActualQuery(t *testing.T) {
	reader := &fakeProductCatalogReader{points: []PricePoint{
		catalogPoint(1, "Tea Green 500g", "2026-09-01", "100.00", "Market A"),
		catalogPoint(2, "Coffee Black 250g", "2026-09-02", "200.00", "Market B"),
	}}
	handler, err := NewProductCatalogHandler("catalog-service-token", reader)
	if err != nil {
		t.Fatal(err)
	}
	request := fmt.Sprintf(`{"tenantId":"%s","ownerUserId":"%s","query":"coffee"}`,
		reader.points[0].TenantID, reader.points[0].OwnerID)
	response := serveCatalogRequest(handler, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "Coffee Black") ||
		strings.Contains(response.Body.String(), "Tea Green") {
		t.Fatalf("search did not filter by supplied query: status=%d body=%s", response.Code, response.Body.String())
	}
}
