package exports

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const maxCoreResponseBytes = 40 << 20

type CoreClient struct {
	http         *http.Client
	baseURL      string
	serviceToken string
}

func NewCoreClient(baseURL, serviceToken string, timeout time.Duration) (*CoreClient, error) {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	parsed, err := url.Parse(baseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return nil, errors.New("export Core client requires an absolute HTTP(S) URL")
	}
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("export Core client requires a service token")
	}
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	return &CoreClient{http: &http.Client{Timeout: timeout}, baseURL: baseURL, serviceToken: serviceToken}, nil
}

func (client *CoreClient) Claim(ctx context.Context) (*Claim, error) {
	request, err := client.newRequest(ctx, http.MethodPost, "/internal/v1/exports/claim", nil)
	if err != nil {
		return nil, err
	}
	response, err := client.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("claim export: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNoContent {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxCoreResponseBytes))
		return nil, nil
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("claim export: Core returned HTTP %d", response.StatusCode)
	}
	var claim Claim
	if err := decodeCoreJSON(response.Body, &claim); err != nil {
		return nil, fmt.Errorf("decode export claim: %w", err)
	}
	return &claim, nil
}

func (client *CoreClient) Page(ctx context.Context, exportID, leaseToken string, after int64, limit int) (SnapshotPage, error) {
	if strings.TrimSpace(exportID) == "" || strings.TrimSpace(leaseToken) == "" || after < 0 || limit < 1 || limit > 1000 {
		return SnapshotPage{}, errors.New("read export page: invalid request")
	}
	path := "/internal/v1/exports/" + url.PathEscape(exportID) + "/rows?leaseToken=" + url.QueryEscape(leaseToken) +
		"&afterRowNumber=" + fmt.Sprint(after) + "&limit=" + fmt.Sprint(limit)
	request, err := client.newRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return SnapshotPage{}, err
	}
	response, err := client.http.Do(request)
	if err != nil {
		return SnapshotPage{}, fmt.Errorf("read export page: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return SnapshotPage{}, fmt.Errorf("read export page: Core returned HTTP %d", response.StatusCode)
	}
	var page SnapshotPage
	if err := decodeCoreJSON(response.Body, &page); err != nil {
		return SnapshotPage{}, fmt.Errorf("decode export page: %w", err)
	}
	return page, nil
}

func (client *CoreClient) Complete(ctx context.Context, exportID string, requestBody CompleteRequest) error {
	if strings.TrimSpace(exportID) == "" {
		return errors.New("complete export: export ID is required")
	}
	return client.post(ctx, "/internal/v1/exports/"+url.PathEscape(exportID)+"/complete", requestBody, "complete export")
}

func (client *CoreClient) Fail(ctx context.Context, exportID string, requestBody FailRequest) error {
	if strings.TrimSpace(exportID) == "" {
		return errors.New("fail export: export ID is required")
	}
	return client.post(ctx, "/internal/v1/exports/"+url.PathEscape(exportID)+"/fail", requestBody, "fail export")
}

func (client *CoreClient) post(ctx context.Context, path string, body any, operation string) error {
	data, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encode %s request: %w", operation, err)
	}
	request, err := client.newRequest(ctx, http.MethodPost, path, bytes.NewReader(data))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.http.Do(request)
	if err != nil {
		return fmt.Errorf("%s: %w", operation, err)
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxCoreResponseBytes))
	if response.StatusCode != http.StatusNoContent {
		return fmt.Errorf("%s: Core returned HTTP %d", operation, response.StatusCode)
	}
	return nil
}

func (client *CoreClient) newRequest(ctx context.Context, method, path string, body io.Reader) (*http.Request, error) {
	request, err := http.NewRequestWithContext(ctx, method, client.baseURL+path, body)
	if err != nil {
		return nil, err
	}
	request.Header.Set("X-Export-Service-Token", client.serviceToken)
	return request, nil
}

func decodeCoreJSON(reader io.Reader, destination any) error {
	decoder := json.NewDecoder(io.LimitReader(reader, maxCoreResponseBytes+1))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return errors.New("response has trailing data")
	}
	return nil
}
