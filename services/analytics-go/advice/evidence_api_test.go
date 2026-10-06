package advice

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestEvidenceHandlerRequiresServiceTokenAndRejectsMalformedFacts(t *testing.T) {
	if _, err := NewEvidenceHandler(""); err == nil {
		t.Fatal("empty service token was accepted")
	}
	handler, err := NewEvidenceHandler("internal-secret")
	if err != nil {
		t.Fatal(err)
	}
	path := "/internal/v1/analytics/advice/evidence-groups"
	get := httptest.NewRecorder()
	handler.ServeHTTP(get, httptest.NewRequest(http.MethodGet, path, nil))
	if get.Code != http.StatusMethodNotAllowed || get.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("GET returned %d and Allow %q", get.Code, get.Header().Get("Allow"))
	}
	request := EvidenceRequest{Items: []EvidenceLine{
		evidenceLine("00000000-0000-4000-8000-000000000031", "coffee", "Кофе", "13.00", "unnecessary", "model", "2026-09-01T10:00:00Z"),
		evidenceLine("00000000-0000-4000-8000-000000000032", "coffee", "Кофе", "14.00", "unnecessary", "model", "2026-09-02T10:00:00Z"),
	}}
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body)))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("missing token returned %d", unauthorized.Code)
	}
	authorized := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
	authorized.Header.Set("Authorization", "Bearer internal-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, authorized)
	if response.Code != http.StatusOK {
		t.Fatalf("valid POST returned %d: %s", response.Code, response.Body.String())
	}
	var groups EvidenceResponse
	if err := json.Unmarshal(response.Body.Bytes(), &groups); err != nil || len(groups.Groups) != 1 ||
		!groups.Groups[0].ModelOnly || groups.Groups[0].Count != 2 {
		t.Fatalf("valid response = %#v, %v", groups, err)
	}
	for _, invalid := range []string{
		`{}`,
		`{"items":null}`,
		`{"items":[],"tenantId":"client-controlled"}`,
		`{"items":[{"itemId":"00000000-0000-4000-8000-000000000031","productKey":"coffee","name":"Кофе","lineSum":"13.00","verdict":"unnecessary","verdictSource":"model","advice":"","purchasedAt":"2026-09-01T10:00:00Z"}]}`,
		`{"items":[{"itemId":"00000000-0000-4000-8000-000000000031","productKey":"coffee","name":"Кофе","lineSum":"13.00","verdict":null,"verdictSource":"model","advice":"","purchasedAt":"2026-09-01T10:00:00Z","itemVersion":1}]}`,
	} {
		call := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(invalid))
		call.Header.Set("Authorization", "Bearer internal-secret")
		failure := httptest.NewRecorder()
		handler.ServeHTTP(failure, call)
		if failure.Code != http.StatusBadRequest {
			t.Errorf("invalid body returned %d: %s", failure.Code, invalid)
		}
	}
}
