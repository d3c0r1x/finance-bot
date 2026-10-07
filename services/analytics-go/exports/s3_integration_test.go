package exports

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

func TestSeaweedS3PrivateExportRoundTrip(t *testing.T) {
	endpointValue := strings.TrimSpace(os.Getenv("FINANCE_EXPORT_IT_S3_ENDPOINT"))
	if endpointValue == "" {
		t.Skip("SeaweedFS S3 integration endpoint is not configured")
	}
	endpoint, err := url.Parse(endpointValue)
	if err != nil || endpoint.Host == "" || (endpoint.Scheme != "http" && endpoint.Scheme != "https") {
		t.Fatal("invalid SeaweedFS integration endpoint")
	}
	accessKey := os.Getenv("FINANCE_EXPORT_IT_S3_ACCESS_KEY")
	secretKey := os.Getenv("FINANCE_EXPORT_IT_S3_SECRET_KEY")
	bucket := "finance-export-it-" + strings.ReplaceAll(uuid.NewString()[:8], "-", "")
	key := "tenants/00000000-0000-4000-8000-000000000002/exports/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000004.csv"
	credentialsProvider := credentials.NewStaticV4(accessKey, secretKey, "")
	setup, err := minio.New(endpoint.Host, &minio.Options{
		Creds: credentialsProvider, Secure: endpoint.Scheme == "https", Region: "us-east-1",
		BucketLookup: minio.BucketLookupPath,
	})
	if err != nil {
		t.Fatal("create integration S3 client")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := setup.MakeBucket(ctx, bucket, minio.MakeBucketOptions{Region: "us-east-1"}); err != nil {
		t.Fatal("create integration bucket")
	}
	defer func() { _ = setup.RemoveBucket(context.Background(), bucket) }()
	store, err := NewS3ObjectStorage(S3Config{
		Endpoint: endpointValue, Region: "us-east-1", Bucket: bucket,
		AccessKey: accessKey, SecretKey: secretKey, PathStyle: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = store.Delete(context.Background(), key) }()
	const body = "\uFEFFДата;Сумма\n2026-10-07 12:00;1.00\n"
	if err := store.Put(ctx, key, "text/csv; charset=utf-8", int64(len(body)), strings.NewReader(body)); err != nil {
		t.Fatal(err)
	}
	object, err := setup.GetObject(ctx, bucket, key, minio.GetObjectOptions{})
	if err != nil {
		t.Fatal(err)
	}
	readBack, readErr := io.ReadAll(object)
	closeErr := object.Close()
	if readErr != nil || closeErr != nil || string(readBack) != body {
		t.Fatalf("private S3 round trip differs: read error=%v close error=%v", readErr, closeErr)
	}
	anonymousURL := endpointValue + "/" + bucket + "/" + key
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, anonymousURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized && response.StatusCode != http.StatusForbidden {
		t.Fatalf("anonymous export read returned HTTP %d, want 401 or 403", response.StatusCode)
	}
	if err := store.Delete(ctx, key); err != nil {
		t.Fatal(err)
	}
}
