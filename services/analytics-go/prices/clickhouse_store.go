package prices

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var clickHouseDatabasePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]{0,62}$`)

type ClickHouseHTTPConfig struct {
	Endpoint   string
	Database   string
	Username   string
	Password   string
	HTTPClient *http.Client
}

type ClickHouseHTTPStore struct {
	endpoint   *url.URL
	database   string
	username   string
	password   string
	httpClient *http.Client
}

type clickHousePricePoint struct {
	TenantID         string  `json:"tenant_id"`
	OwnerID          string  `json:"owner_user_id"`
	ReceiptID        string  `json:"receipt_id"`
	TransactionID    string  `json:"transaction_id"`
	ItemID           string  `json:"item_id"`
	EventID          string  `json:"event_id"`
	AggregateVersion uint64  `json:"aggregate_version"`
	ReceiptDate      string  `json:"receipt_date"`
	PurchasedAt      string  `json:"purchased_at"`
	RecordedAt       string  `json:"recorded_at"`
	Merchant         *string `json:"merchant"`
	Name             string  `json:"name"`
	Quantity         string  `json:"quantity"`
	LineSum          string  `json:"line_sum"`
	UnitPrice        string  `json:"unit_price"`
}

type clickHouseHistoryRow struct {
	TenantID         string  `json:"tenant_id"`
	OwnerID          string  `json:"owner_user_id"`
	ReceiptID        string  `json:"receipt_id"`
	TransactionID    string  `json:"transaction_id"`
	ItemID           string  `json:"item_id"`
	EventID          string  `json:"event_id"`
	AggregateVersion uint64  `json:"aggregate_version"`
	ReceiptDate      string  `json:"receipt_date"`
	PurchasedAt      string  `json:"purchased_at"`
	RecordedAt       string  `json:"recorded_at"`
	Merchant         *string `json:"merchant"`
	Name             string  `json:"name"`
	Quantity         string  `json:"quantity"`
	LineSum          string  `json:"line_sum"`
	UnitPrice        string  `json:"unit_price"`
}

func NewClickHouseHTTPStore(config ClickHouseHTTPConfig) (*ClickHouseHTTPStore, error) {
	endpoint, err := url.Parse(config.Endpoint)
	if err != nil || endpoint.Host == "" || (endpoint.Scheme != "http" && endpoint.Scheme != "https") ||
		endpoint.User != nil || endpoint.Fragment != "" {
		return nil, errors.New("ClickHouse endpoint must be an HTTP(S) URL without embedded credentials or fragment")
	}
	if endpoint.Scheme != "https" && !isLoopbackHost(endpoint.Hostname()) {
		return nil, errors.New("ClickHouse HTTP is allowed only for loopback development; use HTTPS remotely")
	}
	if !clickHouseDatabasePattern.MatchString(config.Database) || strings.TrimSpace(config.Username) == "" ||
		strings.TrimSpace(config.Password) == "" {
		return nil, errors.New("ClickHouse database and service credentials are required")
	}
	httpClient := config.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 10 * time.Second}
	}
	return &ClickHouseHTTPStore{
		endpoint: endpoint, database: config.Database, username: config.Username,
		password: config.Password, httpClient: httpClient,
	}, nil
}

func (store *ClickHouseHTTPStore) InsertPricePoints(ctx context.Context, points []PricePoint) error {
	if len(points) == 0 {
		return nil
	}
	var body bytes.Buffer
	body.WriteString("INSERT INTO receipt_price_items FORMAT JSONEachRow\n")
	encoder := json.NewEncoder(&body)
	encoder.SetEscapeHTML(false)
	for _, point := range points {
		if err := validatePricePoint(point); err != nil {
			return err
		}
		row := clickHousePricePoint{
			TenantID: point.TenantID, OwnerID: point.OwnerID, ReceiptID: point.ReceiptID,
			TransactionID: point.TransactionID, ItemID: point.ItemID, EventID: point.EventID,
			AggregateVersion: point.AggregateVersion, ReceiptDate: point.ReceiptDate,
			PurchasedAt: point.PurchasedAt.UTC().Format("2006-01-02 15:04:05.000000"),
			RecordedAt:  point.RecordedAt.UTC().Format("2006-01-02 15:04:05.000000"),
			Merchant:    point.Merchant, Name: point.Name, Quantity: point.Quantity,
			LineSum: point.LineSum, UnitPrice: point.UnitPrice,
		}
		if err := encoder.Encode(row); err != nil {
			return fmt.Errorf("encode ClickHouse price point: %w", err)
		}
	}

	_, err := store.execute(ctx, body.Bytes(), nil)
	return err
}

func (store *ClickHouseHTTPStore) History(ctx context.Context, tenantID, ownerID string,
	before time.Time, limit int) ([]PricePoint, error) {
	if !eventUUIDPattern.MatchString(tenantID) || !eventUUIDPattern.MatchString(ownerID) || before.IsZero() ||
		limit < 1 || limit > 5000 {
		return nil, errors.New("price history requires valid tenant/member IDs, cutoff and limit from 1 to 5000")
	}
	query := `
		SELECT tenant_id, owner_user_id, receipt_id, transaction_id, item_id, event_id, aggregate_version,
		       toString(receipt_date) AS receipt_date,
	       formatDateTime(purchased_at, '%Y-%m-%d %H:%i:%S.%f', 'UTC') AS purchased_at,
	       formatDateTime(recorded_at, '%Y-%m-%d %H:%i:%S.%f', 'UTC') AS recorded_at,
	       merchant, name, toString(quantity) AS quantity, toString(line_sum) AS line_sum,
	       toString(unit_price) AS unit_price
		FROM receipt_price_items FINAL
		WHERE tenant_id = {tenant_id:UUID}
		  AND owner_user_id = {owner_user_id:UUID}
		  AND receipt_price_items.purchased_at < {before:DateTime64(6, 'UTC')}
		ORDER BY receipt_price_items.purchased_at DESC, receipt_id, item_id
		LIMIT {limit:UInt16}
		FORMAT JSONEachRow
		`
	parameters := map[string]string{
		"param_tenant_id":     tenantID,
		"param_owner_user_id": ownerID,
		"param_before":        before.UTC().Format("2006-01-02 15:04:05.000000"),
		"param_limit":         fmt.Sprint(limit),
	}
	body, err := store.execute(ctx, []byte(query), parameters)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	points := make([]PricePoint, 0)
	for {
		var row clickHouseHistoryRow
		if err := decoder.Decode(&row); err == io.EOF {
			break
		} else if err != nil {
			return nil, fmt.Errorf("decode ClickHouse price history row: %w", err)
		}
		purchasedAt, err := parseClickHouseDateTime(row.PurchasedAt)
		if err != nil {
			return nil, fmt.Errorf("ClickHouse returned invalid purchased_at: %w", err)
		}
		recordedAt, err := parseClickHouseDateTime(row.RecordedAt)
		if err != nil {
			return nil, fmt.Errorf("ClickHouse returned invalid recorded_at: %w", err)
		}
		point := PricePoint{
			TenantID: row.TenantID, OwnerID: row.OwnerID, ReceiptID: row.ReceiptID,
			TransactionID: row.TransactionID, ItemID: row.ItemID, EventID: row.EventID,
			AggregateVersion: row.AggregateVersion, ReceiptDate: row.ReceiptDate,
			PurchasedAt: purchasedAt, RecordedAt: recordedAt, Merchant: row.Merchant,
			Name: row.Name, Quantity: row.Quantity, LineSum: row.LineSum, UnitPrice: row.UnitPrice,
		}
		if err := validatePricePoint(point); err != nil {
			return nil, fmt.Errorf("ClickHouse returned invalid price history row: %w", err)
		}
		if point.TenantID != tenantID || point.OwnerID != ownerID {
			return nil, errors.New("ClickHouse returned a price row outside the requested tenant/member scope")
		}
		points = append(points, point)
	}
	return points, nil
}

func (store *ClickHouseHTTPStore) Ping(ctx context.Context) error {
	body, err := store.execute(ctx, []byte("SELECT 1 FORMAT TabSeparated"), nil)
	if err != nil {
		return err
	}
	if strings.TrimSpace(string(body)) != "1" {
		return errors.New("ClickHouse readiness query returned an unexpected result")
	}
	return nil
}

func (store *ClickHouseHTTPStore) execute(ctx context.Context, body []byte, parameters map[string]string) ([]byte, error) {
	endpoint := *store.endpoint
	query := endpoint.Query()
	query.Set("database", store.database)
	query.Set("wait_end_of_query", "1")
	for name, value := range parameters {
		query.Set(name, value)
	}
	endpoint.RawQuery = query.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create ClickHouse request: %w", err)
	}
	request.Header.Set("Content-Type", "text/plain; charset=utf-8")
	request.SetBasicAuth(store.username, store.password)
	response, err := store.httpClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("execute ClickHouse query: %w", err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, 8*1024*1024+1))
	if err != nil {
		return nil, fmt.Errorf("read ClickHouse response: %w", err)
	}
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		if len(responseBody) > 4096 {
			responseBody = responseBody[:4096]
		}
		return nil, fmt.Errorf("ClickHouse query returned HTTP %d: %s", response.StatusCode, strings.TrimSpace(string(responseBody)))
	}
	if len(responseBody) > 8*1024*1024 {
		return nil, errors.New("ClickHouse response exceeded 8 MiB limit")
	}
	return responseBody, nil
}

func parseClickHouseDateTime(value string) (time.Time, error) {
	for _, layout := range []string{"2006-01-02 15:04:05.000000", "2006-01-02 15:04:05", time.RFC3339Nano} {
		if parsed, err := time.Parse(layout, value); err == nil {
			return parsed, nil
		}
	}
	return time.Time{}, errors.New("timestamp is not a supported ClickHouse UTC format")
}

func validatePricePoint(point PricePoint) error {
	for field, value := range map[string]string{
		"tenant_id": point.TenantID, "owner_user_id": point.OwnerID, "receipt_id": point.ReceiptID,
		"transaction_id": point.TransactionID, "item_id": point.ItemID, "event_id": point.EventID,
	} {
		if !eventUUIDPattern.MatchString(value) {
			return fmt.Errorf("price point has invalid %s", field)
		}
	}
	if point.AggregateVersion == 0 || strings.TrimSpace(point.Name) == "" || point.PurchasedAt.IsZero() || point.RecordedAt.IsZero() {
		return errors.New("price point is missing version, product name or event timestamps")
	}
	if _, err := time.Parse(time.DateOnly, point.ReceiptDate); err != nil {
		return errors.New("price point has invalid receipt date")
	}
	expectedUnit, err := UnitPrice(point.LineSum, point.Quantity)
	if err != nil {
		return fmt.Errorf("price point has invalid paid line: %w", err)
	}
	if point.UnitPrice != expectedUnit {
		return errors.New("price point unit price does not match its paid line")
	}
	return nil
}

func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	address := net.ParseIP(host)
	return address != nil && address.IsLoopback()
}
