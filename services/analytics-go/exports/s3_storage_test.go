package exports

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestS3ObjectStorageUploadsPrivateCSVAndDeletesFailedObject(t *testing.T) {
	var methods []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		methods = append(methods, r.Method)
		if !strings.HasPrefix(r.URL.Path, "/exports-private/tenants/") {
			t.Errorf("S3 path = %q", r.URL.Path)
		}
		if r.Header.Get("Authorization") == "" {
			t.Error("S3 request is unsigned")
		}
		if r.Method == http.MethodPut {
			if r.Header.Get("Content-Type") != "text/csv; charset=utf-8" {
				t.Errorf("content type = %q", r.Header.Get("Content-Type"))
			}
			body, err := io.ReadAll(r.Body)
			if err != nil || !strings.Contains(string(body), "csv bytes") {
				t.Errorf("upload body = %q, err = %v", body, err)
			}
			w.Header().Set("ETag", `"etag"`)
			return
		}
		if r.Method != http.MethodDelete {
			t.Errorf("unexpected method %s", r.Method)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	store, err := NewS3ObjectStorage(S3Config{
		Endpoint: server.URL, Region: "us-east-1", Bucket: "exports-private",
		AccessKey: "access", SecretKey: "secret", PathStyle: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	key := "tenants/00000000-0000-4000-8000-000000000002/exports/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000004.csv"
	if err := store.Put(context.Background(), key, "text/csv; charset=utf-8", 9, strings.NewReader("csv bytes")); err != nil {
		t.Fatal(err)
	}
	if err := store.Delete(context.Background(), key); err != nil {
		t.Fatal(err)
	}
	if strings.Join(methods, ",") != "PUT,DELETE" {
		t.Fatalf("S3 methods = %v", methods)
	}
}

func TestNewS3ObjectStorageRejectsInvalidOrIncompleteConfiguration(t *testing.T) {
	for name, cfg := range map[string]S3Config{
		"missing endpoint":    {Region: "us-east-1", Bucket: "private", AccessKey: "access", SecretKey: "secret"},
		"non-http endpoint":   {Endpoint: "file:///tmp/s3", Region: "us-east-1", Bucket: "private", AccessKey: "access", SecretKey: "secret"},
		"missing credentials": {Endpoint: "http://127.0.0.1:9000", Region: "us-east-1", Bucket: "private"},
		"missing bucket":      {Endpoint: "http://127.0.0.1:9000", Region: "us-east-1", AccessKey: "access", SecretKey: "secret"},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := NewS3ObjectStorage(cfg); err == nil {
				t.Fatal("expected invalid configuration to fail")
			}
		})
	}
}
