package advice

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRecalculationImpactHandlerRequiresServiceTokenAndReturnsDelta(t *testing.T) {
	handler, err := NewRecalculationImpactHandler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	before := goldenRequest()
	before.Items = []WasteLine{wasteTestLine("123e4567-e89b-42d3-a456-426614174000", "chips", "Chips", "125.00", "model", "2026-10-04T10:00:00Z")}
	after := before
	after.Items = append([]WasteLine(nil), before.Items...)
	after.Items[0].Verdict = "useful"
	after.Items[0].VerdictSource = "rule"
	body, err := json.Marshal(RecalculationImpactRequest{Before: &before, After: &after})
	if err != nil {
		t.Fatal(err)
	}

	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/recalculation-impact", bytes.NewReader(body)))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d", unauthorized.Code)
	}

	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/recalculation-impact", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer internal-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("authorized status = %d: %s", response.Code, response.Body.String())
	}
	var result RecalculationImpact
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.OptionalSpendDelta == nil || *result.OptionalSpendDelta != "-125.00" {
		t.Fatalf("impact response = %#v", result)
	}
}
