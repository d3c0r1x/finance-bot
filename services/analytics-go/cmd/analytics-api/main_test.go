package main

import (
	"net/http/httptest"
	"testing"
)

func TestConfiguredF43WorkerIsOptIn(t *testing.T) {
	worker, err := configuredF43Worker("", "", "", "")
	if err != nil || worker != nil {
		t.Fatalf("disabled config = (%v, %v)", worker, err)
	}
	if _, err := configuredF43Worker("sometimes", "", "", ""); err == nil {
		t.Fatal("expected strict boolean validation")
	}
}

func TestConfiguredF43WorkerRequiresCoreUrlAndTokenWhenEnabled(t *testing.T) {
	if _, err := configuredF43Worker("true", "", "secret", ""); err == nil {
		t.Fatal("expected missing Core URL error")
	}
	if _, err := configuredF43Worker("true", "http://core.local", "", ""); err == nil {
		t.Fatal("expected missing service token error")
	}
	if _, err := configuredF43Worker("true", "http://core.local", "secret", "0s"); err == nil {
		t.Fatal("expected non-positive poll interval error")
	}
}

func TestConfiguredF43WorkerAcceptsValidConfiguration(t *testing.T) {
	worker, err := configuredF43Worker("true", "http://core.local", "secret", "250ms")
	if err != nil || worker == nil {
		t.Fatalf("valid config = (%v, %v)", worker, err)
	}
}

func TestConfiguredExportWorkerIsOptInAndValidatesSettings(t *testing.T) {
	if worker, err := configuredExportWorker(func(string) string { return "" }); err != nil || worker != nil {
		t.Fatalf("disabled export worker = (%v, %v)", worker, err)
	}
	if _, err := configuredExportWorker(mapEnvironment(map[string]string{"CSV_EXPORT_WORKER_ENABLED": "sometimes"})); err == nil {
		t.Fatal("expected strict boolean validation")
	}
	if _, err := configuredExportWorker(mapEnvironment(map[string]string{"CSV_EXPORT_WORKER_ENABLED": "true"})); err == nil {
		t.Fatal("expected missing Core and S3 settings to fail")
	}
}

func TestConfiguredExportWorkerAcceptsCoreAndPrivateS3Settings(t *testing.T) {
	server := httptest.NewServer(nil)
	defer server.Close()
	environment := map[string]string{
		"CSV_EXPORT_WORKER_ENABLED":       "true",
		"CSV_EXPORT_WORKER_POLL_INTERVAL": "250ms",
		"CSV_EXPORT_WORKER_PAGE_SIZE":     "250",
		"ANALYTICS_CORE_URL":              server.URL,
		"FINANCE_EXPORTS_SERVICE_TOKEN":   "service-secret",
		"FINANCE_EXPORTS_S3_ENDPOINT":     "http://127.0.0.1:9000",
		"FINANCE_EXPORTS_S3_REGION":       "us-east-1",
		"FINANCE_EXPORTS_S3_BUCKET":       "finance-exports-private",
		"FINANCE_EXPORTS_S3_ACCESS_KEY":   "access",
		"FINANCE_EXPORTS_S3_SECRET_KEY":   "secret",
		"FINANCE_EXPORTS_S3_PATH_STYLE":   "true",
	}
	worker, err := configuredExportWorker(mapEnvironment(environment))
	if err != nil || worker == nil {
		t.Fatalf("valid export config = (%v, %v)", worker, err)
	}
}

func mapEnvironment(values map[string]string) func(string) string {
	return func(key string) string { return values[key] }
}
