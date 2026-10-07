package exports

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	maxSnapshotRows = 100_000
	maxObjectBytes  = 128 << 20
)

type Claim struct {
	ID                string    `json:"id"`
	TenantID          string    `json:"tenantId"`
	RequesterUserID   string    `json:"requesterUserId"`
	FormatVersion     string    `json:"formatVersion"`
	FromDate          string    `json:"fromDate"`
	ToDate            string    `json:"toDate"`
	MemberID          *string   `json:"memberId"`
	AllMembers        bool      `json:"allMembers"`
	RequesterTimezone string    `json:"requesterTimezone"`
	SnapshotAt        time.Time `json:"snapshotAt"`
	RowCount          int       `json:"rowCount"`
	LeaseToken        string    `json:"leaseToken"`
	LeaseExpiresAt    time.Time `json:"leaseExpiresAt"`
	AttemptCount      int       `json:"attemptCount"`
}

type SnapshotRow struct {
	RowNumber       int64     `json:"rowNumber"`
	TransactionID   string    `json:"transactionId"`
	OwnerUserID     string    `json:"ownerUserId"`
	OccurredAt      time.Time `json:"occurredAt"`
	Amount          string    `json:"amount"`
	Currency        string    `json:"currency"`
	CategoryCode    string    `json:"categoryCode"`
	SubcategoryCode string    `json:"subcategoryCode"`
	Description     string    `json:"description"`
	TransactionType string    `json:"transactionType"`
	DebtTarget      string    `json:"debtTarget"`
	Source          string    `json:"source"`
	TelegramID      string    `json:"telegramId"`
}

type SnapshotPage struct {
	ExportID           string        `json:"exportId"`
	FormatVersion      string        `json:"formatVersion"`
	RequesterTimezone  string        `json:"requesterTimezone"`
	SnapshotAt         time.Time     `json:"snapshotAt"`
	RowCount           int           `json:"rowCount"`
	Rows               []SnapshotRow `json:"rows"`
	NextAfterRowNumber int64         `json:"nextAfterRowNumber"`
}

type CompleteRequest struct {
	LeaseToken string `json:"leaseToken"`
	ObjectKey  string `json:"objectKey"`
	ByteCount  int64  `json:"byteCount"`
	SHA256     string `json:"sha256"`
}

type FailRequest struct {
	LeaseToken string `json:"leaseToken"`
	ErrorCode  string `json:"errorCode"`
	Retryable  bool   `json:"retryable"`
}

type Core interface {
	Claim(context.Context) (*Claim, error)
	Page(context.Context, string, string, int64, int) (SnapshotPage, error)
	Complete(context.Context, string, CompleteRequest) error
	Fail(context.Context, string, FailRequest) error
}

type ObjectStorage interface {
	Put(context.Context, string, string, int64, io.Reader) error
	Delete(context.Context, string) error
}

type WorkerConfig struct {
	PageSize     int
	PollInterval time.Duration
	TempDir      string
}

type Worker struct {
	core      Core
	storage   ObjectStorage
	config    WorkerConfig
	processMu sync.Mutex
}

func NewWorker(core Core, storage ObjectStorage, config WorkerConfig) (*Worker, error) {
	if core == nil || storage == nil {
		return nil, errors.New("export worker requires Core and private object storage")
	}
	if config.PageSize == 0 {
		config.PageSize = 500
	}
	if config.PageSize < 1 || config.PageSize > 1000 {
		return nil, errors.New("export worker page size must be from 1 to 1000")
	}
	if config.PollInterval <= 0 {
		config.PollInterval = time.Second
	}
	return &Worker{core: core, storage: storage, config: config}, nil
}

func (worker *Worker) ProcessOne(ctx context.Context) (bool, error) {
	worker.processMu.Lock()
	defer worker.processMu.Unlock()

	claim, err := worker.core.Claim(ctx)
	if err != nil || claim == nil {
		return false, err
	}
	if claim.RowCount > maxSnapshotRows {
		return true, worker.fail(ctx, claim, "row_limit_exceeded", false, errors.New("export snapshot exceeds row limit"))
	}
	if err := validateClaim(claim); err != nil {
		return true, worker.fail(ctx, claim, "invalid_export_snapshot", false, err)
	}
	zone, err := time.LoadLocation(claim.RequesterTimezone)
	if err != nil {
		return true, worker.fail(ctx, claim, "invalid_export_timezone", false, err)
	}
	file, err := os.CreateTemp(worker.config.TempDir, "finance-export-*.csv")
	if err != nil {
		return true, worker.fail(ctx, claim, "export_storage_unavailable", true, err)
	}
	tempPath := file.Name()
	defer func() {
		_ = file.Close()
		_ = os.Remove(tempPath)
	}()
	if err := worker.writeSnapshot(ctx, &boundedWriter{writer: file, remaining: maxObjectBytes}, claim, zone); err != nil {
		return true, worker.fail(ctx, claim, exportErrorCode(err), retryableExportError(err), err)
	}
	if err := file.Sync(); err != nil {
		return true, worker.fail(ctx, claim, "export_storage_unavailable", true, err)
	}
	stat, err := file.Stat()
	if err != nil {
		return true, worker.fail(ctx, claim, "export_storage_unavailable", true, err)
	}
	if stat.Size() < 1 || stat.Size() > maxObjectBytes {
		return true, worker.fail(ctx, claim, "export_size_limit", false, errors.New("export object size is outside allowed bounds"))
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return true, worker.fail(ctx, claim, "export_storage_unavailable", true, err)
	}
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return true, worker.fail(ctx, claim, "export_storage_unavailable", true, err)
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return true, worker.fail(ctx, claim, "export_storage_unavailable", true, err)
	}
	objectKey := fmt.Sprintf("tenants/%s/exports/%s/%s.csv", claim.TenantID, claim.ID, claim.LeaseToken)
	if err := worker.storage.Put(ctx, objectKey, "text/csv; charset=utf-8", stat.Size(), file); err != nil {
		cleanupErr := worker.storage.Delete(context.WithoutCancel(ctx), objectKey)
		return true, worker.fail(ctx, claim, "export_upload_failed", true, errors.Join(err, cleanupErr))
	}
	complete := CompleteRequest{
		LeaseToken: claim.LeaseToken, ObjectKey: objectKey, ByteCount: stat.Size(),
		SHA256: hex.EncodeToString(hash.Sum(nil)),
	}
	if err := worker.core.Complete(ctx, claim.ID, complete); err != nil {
		// Completion can succeed even when its HTTP acknowledgement is lost. Keep object for idempotent retry.
		return true, fmt.Errorf("complete export %s: %w", claim.ID, err)
	}
	return true, nil
}

func (worker *Worker) writeSnapshot(ctx context.Context, output io.Writer, claim *Claim, zone *time.Location) error {
	csvWriter, err := NewCSVWriter(output, zone)
	if err != nil {
		return fmt.Errorf("create CSV: %w", err)
	}
	after := int64(0)
	written := 0
	for written < claim.RowCount {
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("export interrupted: %w", err)
		}
		page, err := worker.core.Page(ctx, claim.ID, claim.LeaseToken, after, worker.config.PageSize)
		if err != nil {
			return fmt.Errorf("read export snapshot page: %w", err)
		}
		if page.ExportID != claim.ID || page.FormatVersion != claim.FormatVersion ||
			page.RequesterTimezone != claim.RequesterTimezone || !page.SnapshotAt.Equal(claim.SnapshotAt) ||
			page.RowCount != claim.RowCount || len(page.Rows) == 0 || len(page.Rows) > worker.config.PageSize {
			return errors.New("export snapshot page metadata is inconsistent")
		}
		last := after
		for _, row := range page.Rows {
			if row.RowNumber <= last || row.RowNumber > int64(claim.RowCount) {
				return errors.New("export snapshot row order is invalid")
			}
			if err := csvWriter.Write(Transaction{
				OccurredAt: row.OccurredAt, Amount: row.Amount, Category: row.CategoryCode,
				Subcategory: row.SubcategoryCode, Description: row.Description, Type: row.TransactionType,
				DebtTarget: row.DebtTarget, Source: row.Source, TelegramID: row.TelegramID,
			}); err != nil {
				return fmt.Errorf("write export CSV row: %w", err)
			}
			last = row.RowNumber
			written++
		}
		if page.NextAfterRowNumber != last || page.NextAfterRowNumber <= after {
			return errors.New("export snapshot page cursor is invalid")
		}
		after = page.NextAfterRowNumber
	}
	if err := csvWriter.Flush(); err != nil {
		return fmt.Errorf("flush export CSV: %w", err)
	}
	return nil
}

func (worker *Worker) fail(ctx context.Context, claim *Claim, code string, retryable bool, cause error) error {
	failErr := worker.core.Fail(ctx, claim.ID, FailRequest{LeaseToken: claim.LeaseToken, ErrorCode: code, Retryable: retryable})
	return errors.Join(cause, failErr)
}

func validateClaim(claim *Claim) error {
	if claim.ID == "" || claim.TenantID == "" || claim.RequesterUserID == "" || claim.LeaseToken == "" ||
		claim.FormatVersion != FormatVersionV1 || claim.RequesterTimezone == "" || claim.SnapshotAt.IsZero() ||
		claim.RowCount < 0 || claim.AttemptCount < 1 || claim.AttemptCount > 5 {
		return errors.New("export claim fields are inconsistent")
	}
	return nil
}

func exportErrorCode(err error) string {
	if err == nil {
		return "export_failed"
	}
	if strings.Contains(err.Error(), "read export snapshot page") {
		return "core_unavailable"
	}
	if strings.Contains(err.Error(), "snapshot") || strings.Contains(err.Error(), "row order") || strings.Contains(err.Error(), "cursor") {
		return "invalid_export_snapshot"
	}
	if strings.Contains(err.Error(), "interrupted") || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return "export_interrupted"
	}
	return "export_write_failed"
}

func retryableExportError(err error) bool {
	return err != nil && (strings.Contains(err.Error(), "interrupted") || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "read export snapshot page"))
}

type boundedWriter struct {
	writer    io.Writer
	remaining int64
}

func (writer *boundedWriter) Write(data []byte) (int, error) {
	if int64(len(data)) > writer.remaining {
		return 0, errors.New("export size limit exceeded")
	}
	n, err := writer.writer.Write(data)
	writer.remaining -= int64(n)
	return n, err
}

func (worker *Worker) Run(ctx context.Context) error {
	for {
		_, err := worker.ProcessOne(ctx)
		if err != nil && ctx.Err() != nil {
			return ctx.Err()
		}
		if err := waitExport(ctx, worker.config.PollInterval); err != nil {
			return err
		}
	}
}

func waitExport(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
