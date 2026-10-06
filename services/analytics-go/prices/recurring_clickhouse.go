package prices

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"time"

	"github.com/d3c0r1x/finance-bot/services/analytics-go/recurring"
)

var recurringAmountPattern = regexp.MustCompile(`^(?:0\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\.[0-9]{1,2})?)$`)

type clickHouseTransactionState struct {
	TenantID         string `json:"tenant_id"`
	OwnerID          string `json:"owner_user_id"`
	TransactionID    string `json:"transaction_id"`
	EventID          string `json:"event_id"`
	AggregateVersion uint64 `json:"aggregate_version"`
	Type             string `json:"type"`
	Amount           string `json:"amount"`
	Currency         string `json:"currency"`
	CategoryCode     string `json:"category_code"`
	Description      string `json:"description"`
	Status           string `json:"status"`
	OccurredAt       string `json:"occurred_at"`
	RecordedAt       string `json:"recorded_at"`
}

type clickHouseTransactionStateRow struct {
	TenantID         string `json:"tenant_id"`
	OwnerID          string `json:"owner_user_id"`
	TransactionID    string `json:"transaction_id"`
	EventID          string `json:"event_id"`
	AggregateVersion uint64 `json:"aggregate_version"`
	Type             string `json:"type"`
	Amount           string `json:"amount"`
	Currency         string `json:"currency"`
	CategoryCode     string `json:"category_code"`
	Description      string `json:"description"`
	Status           string `json:"status"`
	OccurredAt       string `json:"occurred_at"`
	RecordedAt       string `json:"recorded_at"`
}

// InsertTransactions stores full-state snapshots; a stable sorting key plus FINAL makes
// retry duplicates and later aggregate versions resolve to one current transaction.
func (store *ClickHouseHTTPStore) InsertTransactions(ctx context.Context, transactions []recurring.Transaction) error {
	if len(transactions) == 0 {
		return nil
	}
	var body bytes.Buffer
	body.WriteString("INSERT INTO transaction_states FORMAT JSONEachRow\n")
	encoder := json.NewEncoder(&body)
	encoder.SetEscapeHTML(false)
	for _, tx := range transactions {
		if err := validateRecurringTransaction(tx); err != nil {
			return err
		}
		row := clickHouseTransactionState{
			TenantID: tx.TenantID, OwnerID: tx.OwnerID, TransactionID: tx.ID, EventID: tx.EventID,
			AggregateVersion: tx.AggregateVersion, Type: tx.Type, Amount: tx.Amount, Currency: tx.Currency,
			CategoryCode: tx.CategoryCode, Description: tx.Description, Status: tx.Status,
			OccurredAt: tx.OccurredAt.UTC().Format("2006-01-02 15:04:05.000000"),
			RecordedAt: tx.RecordedAt.UTC().Format("2006-01-02 15:04:05.000000"),
		}
		if err := encoder.Encode(row); err != nil {
			return fmt.Errorf("encode transaction state: %w", err)
		}
	}
	_, err := store.execute(ctx, body.Bytes(), nil)
	return err
}

// Transactions reads a bounded, deduplicated full history for one member. It intentionally
// does not push financial-time filtering into ClickHouse: a later update may move that time.
func (store *ClickHouseHTTPStore) Transactions(ctx context.Context, tenantID, ownerID string, limit int) ([]recurring.Transaction, error) {
	if !eventUUIDPattern.MatchString(tenantID) || !eventUUIDPattern.MatchString(ownerID) || limit < 1 || limit > 5000 {
		return nil, errors.New("transaction history requires valid tenant/member IDs and limit from 1 to 5000")
	}
	query := `
		SELECT tenant_id, owner_user_id, transaction_id, event_id, aggregate_version, type,
		       toString(amount) AS amount, currency, category_code, description, status,
		       formatDateTime(occurred_at, '%Y-%m-%d %H:%i:%S.%f', 'UTC') AS occurred_at,
		       formatDateTime(recorded_at, '%Y-%m-%d %H:%i:%S.%f', 'UTC') AS recorded_at
		FROM transaction_states FINAL
		WHERE tenant_id = {tenant_id:UUID}
		  AND owner_user_id = {owner_user_id:UUID}
		ORDER BY occurred_at DESC, transaction_id
		LIMIT {limit:UInt16}
		FORMAT JSONEachRow
	`
	parameters := map[string]string{
		"param_tenant_id": tenantID, "param_owner_user_id": ownerID, "param_limit": fmt.Sprint(limit),
	}
	body, err := store.execute(ctx, []byte(query), parameters)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	transactions := make([]recurring.Transaction, 0)
	for {
		var row clickHouseTransactionStateRow
		if err := decoder.Decode(&row); err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, fmt.Errorf("decode transaction state row: %w", err)
		}
		if row.TenantID != tenantID || row.OwnerID != ownerID {
			return nil, errors.New("ClickHouse returned transaction outside requested scope")
		}
		occurred, err := time.ParseInLocation("2006-01-02 15:04:05.000000", row.OccurredAt, time.UTC)
		if err != nil {
			return nil, errors.New("ClickHouse returned invalid transaction time")
		}
		recorded, err := time.ParseInLocation("2006-01-02 15:04:05.000000", row.RecordedAt, time.UTC)
		if err != nil {
			return nil, errors.New("ClickHouse returned invalid recorded time")
		}
		transactions = append(transactions, recurring.Transaction{
			TenantID: row.TenantID, OwnerID: row.OwnerID, ID: row.TransactionID, EventID: row.EventID,
			AggregateVersion: row.AggregateVersion, Type: row.Type, Amount: row.Amount, Currency: row.Currency,
			CategoryCode: row.CategoryCode, Description: row.Description, Status: row.Status,
			OccurredAt: occurred, RecordedAt: recorded,
		})
	}
	return transactions, nil
}

func validateRecurringTransaction(tx recurring.Transaction) error {
	if !eventUUIDPattern.MatchString(tx.TenantID) || !eventUUIDPattern.MatchString(tx.OwnerID) ||
		!eventUUIDPattern.MatchString(tx.ID) || !eventUUIDPattern.MatchString(tx.EventID) ||
		tx.AggregateVersion == 0 || tx.OccurredAt.IsZero() || tx.RecordedAt.IsZero() {
		return errors.New("transaction projection identity or timestamps are invalid")
	}
	if tx.Type != "expense" && tx.Type != "income" && tx.Type != "refund" && tx.Type != "debt_payment" && tx.Type != "transfer" {
		return errors.New("transaction projection type is invalid")
	}
	if tx.Status != "posted" && tx.Status != "voided" {
		return errors.New("transaction projection status is invalid")
	}
	if tx.Currency != "RUB" || !recurringAmountPattern.MatchString(strings.TrimSpace(tx.Amount)) {
		return errors.New("transaction projection money is invalid")
	}
	return nil
}
