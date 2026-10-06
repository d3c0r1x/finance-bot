package advice

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestF44HandlerRequiresPostAndServiceBearer(t *testing.T) {
	handler, err := NewF44Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	get := httptest.NewRecorder()
	handler.ServeHTTP(get, httptest.NewRequest(http.MethodGet, "/internal/v1/analytics/goals/candidates", nil))
	if get.Code != http.StatusMethodNotAllowed || get.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("GET returned %d, Allow=%q", get.Code, get.Header().Get("Allow"))
	}
	body, err := json.Marshal(f44ProductRequest("count"))
	if err != nil {
		t.Fatal(err)
	}
	for _, authorization := range []string{"", "Bearer wrong", "Basic internal-secret"} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/candidates", bytes.NewReader(body))
		request.Header.Set("Authorization", authorization)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized {
			t.Errorf("Authorization %q returned %d", authorization, response.Code)
		}
	}
}

func TestF44HandlerReturnsVersionedCandidates(t *testing.T) {
	handler, err := NewF44Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(f44ProductRequest("count"))
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/candidates", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer internal-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("POST returned %d: %s", response.Code, response.Body.String())
	}
	var report F44Report
	if err := json.Unmarshal(response.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if report.AlgorithmVersion != F44AlgorithmVersion || report.InputWatermark != "8" || report.Unit != "count" || len(report.Products) != 2 {
		t.Fatalf("unexpected F44 report: %#v", report)
	}
}

func TestF44HandlerRejectsUnknownTrailingAndMissingFields(t *testing.T) {
	handler, err := NewF44Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	valid, _ := json.Marshal(f44ProductRequest("count"))
	unknown := strings.TrimSuffix(string(valid), "}") + `,"tenantId":"client-controlled"}`
	for _, body := range []string{unknown, string(valid) + ` {}`, `{"inputWatermark":`, strings.Replace(string(valid), `"decisions":[`, `"decisions":null,`, 1)} {
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/candidates", strings.NewReader(body))
		request.Header.Set("Authorization", "Bearer internal-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest {
			t.Errorf("invalid body returned %d: %s", response.Code, response.Body.String())
		}
	}
	var object map[string]any
	if err := json.Unmarshal(valid, &object); err != nil {
		t.Fatal(err)
	}
	delete(object, "inputWatermark")
	missing, _ := json.Marshal(object)
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/candidates", bytes.NewReader(missing))
	request.Header.Set("Authorization", "Bearer internal-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("missing watermark returned %d", response.Code)
	}
}

func TestF44HandlerRejectsOversizedRequest(t *testing.T) {
	handler, err := NewF44Handler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	body := bytes.Repeat([]byte(" "), maxF43RequestBytes+1)
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/analytics/goals/candidates", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer internal-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized request returned %d, want 413", response.Code)
	}
}
