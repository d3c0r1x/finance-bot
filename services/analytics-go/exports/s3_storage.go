package exports

import (
	"context"
	"errors"
	"io"
	"net/url"
	"strings"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type S3Config struct {
	Endpoint  string
	Region    string
	Bucket    string
	AccessKey string
	SecretKey string
	PathStyle bool
}

type S3ObjectStorage struct {
	client *minio.Client
	bucket string
}

func NewS3ObjectStorage(config S3Config) (*S3ObjectStorage, error) {
	endpoint, err := url.Parse(strings.TrimSpace(config.Endpoint))
	if err != nil || (endpoint.Scheme != "http" && endpoint.Scheme != "https") || endpoint.Host == "" ||
		endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" ||
		(endpoint.Path != "" && endpoint.Path != "/") {
		return nil, errors.New("export S3 endpoint must be an absolute HTTP(S) origin")
	}
	if strings.TrimSpace(config.Region) == "" || strings.TrimSpace(config.Bucket) == "" ||
		strings.TrimSpace(config.AccessKey) == "" || strings.TrimSpace(config.SecretKey) == "" {
		return nil, errors.New("export S3 region, bucket, access key, and secret key are required")
	}
	lookup := minio.BucketLookupAuto
	if config.PathStyle {
		lookup = minio.BucketLookupPath
	}
	client, err := minio.New(endpoint.Host, &minio.Options{
		Creds:  credentials.NewStaticV4(config.AccessKey, config.SecretKey, ""),
		Secure: endpoint.Scheme == "https", Region: config.Region, BucketLookup: lookup,
	})
	if err != nil {
		return nil, errors.New("create export S3 client: invalid configuration")
	}
	return &S3ObjectStorage{client: client, bucket: config.Bucket}, nil
}

func (storage *S3ObjectStorage) Put(ctx context.Context, key, contentType string, size int64, body io.Reader) error {
	if storage == nil || storage.client == nil || body == nil || size < 1 || size > maxObjectBytes ||
		!strings.HasPrefix(key, "tenants/") || contentType != "text/csv; charset=utf-8" {
		return errors.New("invalid export S3 upload")
	}
	_, err := storage.client.PutObject(ctx, storage.bucket, key, body, size, minio.PutObjectOptions{
		ContentType: contentType,
		PartSize:    5 << 20,
		NumThreads:  2,
	})
	if err != nil {
		return errors.New("upload export object to S3")
	}
	return nil
}

func (storage *S3ObjectStorage) Delete(ctx context.Context, key string) error {
	if storage == nil || storage.client == nil || !strings.HasPrefix(key, "tenants/") {
		return errors.New("invalid export S3 delete")
	}
	if err := storage.client.RemoveObject(ctx, storage.bucket, key, minio.RemoveObjectOptions{}); err != nil {
		return errors.New("delete partial export object from S3")
	}
	return nil
}
