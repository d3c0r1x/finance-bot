package advice

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestF43HandlerRequiresPostAndServiceBearer(t *testing.T) {
	handler, err := NewF43Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	get := httptest.NewRecorder()
	handler.ServeHTTP(get, httptest.NewRequest(http.MethodGet, "/internal/v1/analytics/advice/f43", nil))
	if get.Code != http.StatusMethodNotAllowed || get.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("GET response = %d, Allow %q", get.Code, get.Header().Get("Allow"))
	}
	body, err := json.Marshal(f43TestRequest("50", "2026-10-07T12:00:00Z", []F43Item{}))
	if err != nil {
		t.Fatal(err)
	}
	for _, authorization := range []string{"", "Bearer wrong", "Basic internal-secret"} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/advice/f43", bytes.NewReader(body))
		request.Header.Set("Authorization", authorization)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized {
			t.Errorf("Authorization %q returned %d", authorization, response.Code)
		}
	}
}

func TestF43HandlerReturnsReportForValidInternalRequest(t *testing.T) {
	handler, err := NewF43Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(f43TestRequest("51", "2026-10-07T12:00:00Z", []F43Item{}))
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/advice/f43", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer internal-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("POST response = %d: %s", response.Code, response.Body.String())
	}
	var report F43Report
	if err := json.Unmarshal(response.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if report.InputWatermark != "51" || report.AlgorithmVersion != F43AlgorithmVersion || report.Completeness != "complete" {
		t.Fatalf("response report = %#v", report)
	}
}

func TestF43HandlerRejectsUnknownTrailingMalformedAndMissingFields(t *testing.T) {
	handler, err := NewF43Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	valid, _ := json.Marshal(f43TestRequest("52", "2026-10-07T12:00:00Z", []F43Item{}))
	unknown := strings.TrimSuffix(string(valid), "}") + `,"tenantId":"client-controlled"}`
	for _, body := range []string{unknown, `{"inputWatermark":`, string(valid) + ` {}`} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/advice/f43", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer internal-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Errorf("body %q returned %d", body, response.Code)
		}
	}

	var object map[string]any
	if err := json.Unmarshal(valid, &object); err != nil {
		t.Fatal(err)
	}
	delete(object, "inputWatermark")
	missing, _ := json.Marshal(object)
	for _, body := range [][]byte{missing} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/advice/f43", bytes.NewReader(body))
		request.Header.Set("Authorization", "Bearer internal-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Errorf("request missing watermark returned %d: %s", response.Code, response.Body.String())
		}
	}
}
