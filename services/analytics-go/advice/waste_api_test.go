package advice

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWasteHandlerRequiresPostAndServiceBearer(t *testing.T) {
	handler, err := NewWasteHandler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}

	get := httptest.NewRecorder()
	handler.ServeHTTP(get, httptest.NewRequest(http.MethodGet, "/internal/v1/analytics/waste", nil))
	if get.Code != http.StatusMethodNotAllowed || get.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("GET response = %d, Allow %q", get.Code, get.Header().Get("Allow"))
	}

	body, err := json.Marshal(goldenRequest())
	if err != nil {
		t.Fatal(err)
	}
	for _, authorization := range []string{"", "Bearer wrong", "Basic internal-secret"} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/waste", bytes.NewReader(body))
		request.Header.Set("Authorization", authorization)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized {
			t.Errorf("Authorization %q returned %d", authorization, response.Code)
		}
	}
}

func TestWasteHandlerReturnsVersionedGoldenReport(t *testing.T) {
	handler, err := NewWasteHandler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(goldenRequest())
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/waste", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer internal-secret")
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("POST response = %d: %s", response.Code, response.Body.String())
	}
	var report WasteReport
	if err := json.Unmarshal(response.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if !report.Available || report.AlgorithmVersion != WasteAlgorithmVersion || report.Completeness != "complete" {
		t.Fatalf("response report = %#v", report)
	}
}

func TestWasteHandlerRejectsUnknownFieldsAndMalformedJson(t *testing.T) {
	handler, err := NewWasteHandler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{
		`{"fromDate":"2026-10-04","toDate":"2026-10-05","asOf":"2026-10-06T00:00:00Z","timeZone":"UTC","items":[],"tenantId":"must-not-be-client-controlled"}`,
		`{"fromDate":`,
		`{} {}`,
	} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/waste", bytes.NewBufferString(body))
		request.Header.Set("Authorization", "Bearer internal-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Errorf("body %q returned %d", body, response.Code)
		}
	}
}

func TestWasteHandlerRejectsMissingRequiredRequestAndLineFields(t *testing.T) {
	handler, err := NewWasteHandler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	request := goldenRequest()
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	var object map[string]any
	if err := json.Unmarshal(body, &object); err != nil {
		t.Fatal(err)
	}
	delete(object, "items")
	missingItems, _ := json.Marshal(object)

	if err := json.Unmarshal(body, &object); err != nil {
		t.Fatal(err)
	}
	items := object["items"].([]any)
	delete(items[0].(map[string]any), "allowed")
	missingAllowed, _ := json.Marshal(object)
	if err := json.Unmarshal(body, &object); err != nil {
		t.Fatal(err)
	}
	items = object["items"].([]any)
	items[0].(map[string]any)["allowed"] = nil
	nullAllowed, _ := json.Marshal(object)

	for _, invalidBody := range [][]byte{missingItems, missingAllowed, nullAllowed} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/waste", bytes.NewReader(invalidBody))
		request.Header.Set("Authorization", "Bearer internal-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Errorf("body missing a required field returned %d: %s", response.Code, strings.TrimSpace(response.Body.String()))
		}
	}
}
