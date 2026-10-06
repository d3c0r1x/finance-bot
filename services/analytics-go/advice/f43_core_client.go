package advice

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

const maxF43CoreBody = 40 << 20

type F43LeasedJob struct {
	ID               string     `json:"id"`
	TenantID         string     `json:"tenantId"`
	OwnerUserID      string     `json:"ownerUserId"`
	InputWatermark   string     `json:"inputWatermark"`
	AlgorithmVersion string     `json:"algorithmVersion"`
	LeaseToken       string     `json:"leaseToken"`
	AttemptCount     int        `json:"attemptCount"`
	Input            F43Request `json:"input"`
}

type F43JobResult struct {
	LeaseToken       string     `json:"leaseToken"`
	InputWatermark   string     `json:"inputWatermark"`
	AlgorithmVersion string     `json:"algorithmVersion"`
	Report           *F43Report `json:"report"`
	ErrorCode        string     `json:"errorCode"`
}

type F43Core interface {
	Claim(context.Context) (*F43LeasedJob, error)
	Complete(context.Context, string, F43JobResult) error
}

type F43CoreClient struct {
	http         *http.Client
	baseURL      string
	serviceToken string
}

func NewF43CoreClient(baseURL, serviceToken string, timeout time.Duration) (*F43CoreClient, error) {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	parsed, err := url.Parse(baseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return nil, errors.New("F43 Core client requires an absolute HTTP(S) URL")
	}
	if strings.TrimSpace(serviceToken) == "" {
		return nil, errors.New("F43 Core client requires a service token")
	}
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	return &F43CoreClient{
		http: &http.Client{Timeout: timeout}, baseURL: baseURL, serviceToken: serviceToken,
	}, nil
}

func (client *F43CoreClient) Claim(ctx context.Context) (*F43LeasedJob, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		client.baseURL+"/internal/v1/analytics/advice-jobs/claim", nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("X-Analytics-Service-Token", client.serviceToken)
	response, err := client.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("claim F43 job: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNoContent {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxF43CoreBody))
		return nil, nil
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("claim F43 job: Core returned HTTP %d", response.StatusCode)
	}
	body, err := readF43CoreBody(response.Body)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var job F43LeasedJob
	if err := decoder.Decode(&job); err != nil {
		return nil, fmt.Errorf("decode F43 lease: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return nil, errors.New("decode F43 lease: trailing response data")
	}
	if job.ID == "" || job.TenantID == "" || job.OwnerUserID == "" || job.LeaseToken == "" ||
		job.InputWatermark == "" || job.InputWatermark != job.Input.InputWatermark ||
		job.AlgorithmVersion != F43AlgorithmVersion || job.AttemptCount < 1 || job.AttemptCount > 5 {
		return nil, errors.New("decode F43 lease: required job fields are inconsistent")
	}
	return &job, nil
}

func (client *F43CoreClient) Complete(ctx context.Context, jobID string, result F43JobResult) error {
	if strings.TrimSpace(jobID) == "" {
		return errors.New("complete F43 job: job ID is required")
	}
	body, err := json.Marshal(result)
	if err != nil {
		return fmt.Errorf("encode F43 result: %w", err)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		client.baseURL+"/internal/v1/analytics/advice-jobs/"+url.PathEscape(jobID)+"/result", bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("X-Analytics-Service-Token", client.serviceToken)
	request.Header.Set("Content-Type", "application/json")
	response, err := client.http.Do(request)
	if err != nil {
		return fmt.Errorf("submit F43 result: %w", err)
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxF43CoreBody))
	if response.StatusCode != http.StatusNoContent {
		return fmt.Errorf("submit F43 result: Core returned HTTP %d", response.StatusCode)
	}
	return nil
}

func readF43CoreBody(reader io.Reader) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(reader, maxF43CoreBody+1))
	if err != nil {
		return nil, fmt.Errorf("read F43 Core response: %w", err)
	}
	if len(body) > maxF43CoreBody {
		return nil, errors.New("F43 Core response exceeds size limit")
	}
	return body, nil
}
