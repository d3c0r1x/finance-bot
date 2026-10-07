package exports

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"
)

type fakeExportCore struct {
	claim       *Claim
	pages       map[int64]SnapshotPage
	pageLimits  []int
	completed   *CompleteRequest
	completeErr error
	failures    []FailRequest
	pageErr     error
}

func (core *fakeExportCore) Claim(context.Context) (*Claim, error) { return core.claim, nil }

func (core *fakeExportCore) Page(_ context.Context, _ string, _ string, after int64, limit int) (SnapshotPage, error) {
	core.pageLimits = append(core.pageLimits, limit)
	if core.pageErr != nil {
		return SnapshotPage{}, core.pageErr
	}
	return core.pages[after], nil
}

func (core *fakeExportCore) Complete(_ context.Context, _ string, request CompleteRequest) error {
	core.completed = &request
	return core.completeErr
}

func (core *fakeExportCore) Fail(_ context.Context, _ string, request FailRequest) error {
	core.failures = append(core.failures, request)
	return nil
}

type fakeExportStorage struct {
	objects map[string][]byte
	putErr  error
	deletes []string
}

func (storage *fakeExportStorage) Put(_ context.Context, key, _ string, size int64, body io.Reader) error {
	if storage.objects == nil {
		storage.objects = make(map[string][]byte)
	}
	data, err := io.ReadAll(body)
	storage.objects[key] = data
	if err != nil {
		return err
	}
	if int64(len(data)) != size {
		return errors.New("declared storage size does not match stream")
	}
	return storage.putErr
}

func (storage *fakeExportStorage) Delete(_ context.Context, key string) error {
	storage.deletes = append(storage.deletes, key)
	delete(storage.objects, key)
	return nil
}

func exportClaim(rowCount int) *Claim {
	return &Claim{
		ID: "00000000-0000-4000-8000-000000000001", TenantID: "00000000-0000-4000-8000-000000000002",
		RequesterUserID: "00000000-0000-4000-8000-000000000003", FormatVersion: FormatVersionV1,
		FromDate: "2026-10-01", ToDate: "2026-10-02", RequesterTimezone: "Europe/Moscow",
		SnapshotAt: time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC), RowCount: rowCount,
		LeaseToken: "00000000-0000-4000-8000-000000000004", AttemptCount: 1,
	}
}

func exportPage(after int64, rows ...SnapshotRow) SnapshotPage {
	next := after
	if len(rows) > 0 {
		next = rows[len(rows)-1].RowNumber
	}
	return SnapshotPage{ExportID: "00000000-0000-4000-8000-000000000001", FormatVersion: FormatVersionV1,
		RequesterTimezone: "Europe/Moscow", SnapshotAt: time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC),
		RowCount: 2, Rows: rows, NextAfterRowNumber: next}
}

func exportRow(number int64, description string) SnapshotRow {
	return SnapshotRow{RowNumber: number, TransactionID: "00000000-0000-4000-8000-000000000010",
		OwnerUserID: "00000000-0000-4000-8000-000000000003", OccurredAt: time.Date(2026, 10, int(number), 8, 0, 0, 0, time.UTC),
		Amount: "125.50", Currency: "RUB", CategoryCode: "food", SubcategoryCode: "groceries",
		Description: description, TransactionType: "expense", Source: "manual"}
}

func TestWorkerStreamsOrderedPagesAndCompletesOnlyAfterPrivateObjectWrite(t *testing.T) {
	core := &fakeExportCore{claim: exportClaim(2), pages: map[int64]SnapshotPage{
		0: exportPage(0, exportRow(1, "first")),
		1: exportPage(1, exportRow(2, "second")),
	}}
	storage := &fakeExportStorage{}
	worker, err := NewWorker(core, storage, WorkerConfig{PageSize: 1, PollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err != nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want processed", processed, err)
	}
	if core.completed == nil {
		t.Fatal("Core completion was not submitted")
	}
	key := core.completed.ObjectKey
	if key != "tenants/00000000-0000-4000-8000-000000000002/exports/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000004.csv" {
		t.Fatalf("object key = %q", key)
	}
	if core.completed.ByteCount < 1 || len(core.completed.SHA256) != 64 {
		t.Fatalf("invalid object metadata: %+v", core.completed)
	}
	data := string(storage.objects[key])
	if !strings.Contains(data, "first") || !strings.Contains(data, "second") || strings.Index(data, "first") > strings.Index(data, "second") {
		t.Fatalf("export rows are missing or unordered: %q", data)
	}
	if len(core.pageLimits) != 2 || core.pageLimits[0] != 1 || core.pageLimits[1] != 1 {
		t.Fatalf("page limits = %v, want bounded pages of 1", core.pageLimits)
	}
	if len(core.failures) != 0 || len(storage.deletes) != 0 {
		t.Fatalf("unexpected cleanup or failure: failures=%+v deletes=%v", core.failures, storage.deletes)
	}
}

func TestWorkerRejectsSnapshotAboveContractLimit(t *testing.T) {
	claim := exportClaim(100_001)
	core := &fakeExportCore{claim: claim}
	storage := &fakeExportStorage{}
	worker, err := NewWorker(core, storage, WorkerConfig{PageSize: 500, PollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err == nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want rejected claimed job", processed, err)
	}
	if core.completed != nil || len(storage.objects) != 0 || len(core.failures) != 1 || core.failures[0].ErrorCode != "row_limit_exceeded" {
		t.Fatalf("oversized snapshot was not rejected before storage: completed=%+v objects=%v failures=%+v", core.completed, storage.objects, core.failures)
	}
}

func TestWorkerDeletesPartialObjectAndDoesNotCompleteWhenStorageFails(t *testing.T) {
	page := exportPage(0, exportRow(1, "first"))
	page.RowCount = 1
	core := &fakeExportCore{claim: exportClaim(1), pages: map[int64]SnapshotPage{
		0: page,
	}}
	storage := &fakeExportStorage{putErr: errors.New("upload interrupted")}
	worker, err := NewWorker(core, storage, WorkerConfig{PageSize: 1, PollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err == nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want processed upload failure", processed, err)
	}
	if core.completed != nil {
		t.Fatalf("Core marked incomplete object ready: %+v", core.completed)
	}
	if len(storage.deletes) != 1 || len(storage.objects) != 0 {
		t.Fatalf("partial object cleanup = deletes %v, objects %v", storage.deletes, storage.objects)
	}
	if len(core.failures) != 1 || !core.failures[0].Retryable {
		t.Fatalf("retryable storage failure not reported: %+v", core.failures)
	}
}

func TestWorkerDoesNotCompleteWhenPageStreamBreaks(t *testing.T) {
	core := &fakeExportCore{claim: exportClaim(2), pages: map[int64]SnapshotPage{
		0: exportPage(0, exportRow(2, "out of order")),
	}}
	storage := &fakeExportStorage{}
	worker, err := NewWorker(core, storage, WorkerConfig{PageSize: 1, PollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err == nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want invalid page failure", processed, err)
	}
	if core.completed != nil || len(storage.deletes) != 0 || len(storage.objects) != 0 || len(core.failures) != 1 {
		t.Fatalf("broken snapshot stream escaped: completed=%+v deletes=%v objects=%v failures=%+v", core.completed, storage.deletes, storage.objects, core.failures)
	}
}

func TestWorkerClassifiesTemporaryCorePageFailureAsRetryable(t *testing.T) {
	core := &fakeExportCore{claim: exportClaim(1), pageErr: errors.New("Core connection reset")}
	storage := &fakeExportStorage{}
	worker, err := NewWorker(core, storage, WorkerConfig{PageSize: 1, PollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err == nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want retriable page error", processed, err)
	}
	if len(core.failures) != 1 || !core.failures[0].Retryable || core.failures[0].ErrorCode != "core_unavailable" {
		t.Fatalf("temporary Core failure = %+v, want retryable core_unavailable", core.failures)
	}
	if core.completed != nil || len(storage.objects) != 0 {
		t.Fatalf("failed page read produced object: completed=%+v objects=%v", core.completed, storage.objects)
	}
}

func TestWorkerStopsInterruptedSnapshotAndNeverUploadsIt(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	core := &fakeExportCore{claim: exportClaim(1)}
	core.pages = map[int64]SnapshotPage{}
	storage := &fakeExportStorage{}
	worker, err := NewWorker(core, storage, WorkerConfig{PageSize: 1, PollInterval: time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	// Cancellation before the first page must leave no stored object or ready job.
	cancel()
	processed, err := worker.ProcessOne(ctx)
	if err == nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want canceled export", processed, err)
	}
	if core.completed != nil || len(storage.objects) != 0 || len(storage.deletes) != 0 {
		t.Fatalf("interrupted snapshot escaped: completed=%+v objects=%v deletes=%v", core.completed, storage.objects, storage.deletes)
	}
	if len(core.failures) != 1 || !core.failures[0].Retryable || core.failures[0].ErrorCode != "export_interrupted" {
		t.Fatalf("interrupted snapshot failure = %+v", core.failures)
	}
}

func TestBoundedWriterNeverWritesBeyondConfiguredLimit(t *testing.T) {
	var output strings.Builder
	writer := &boundedWriter{writer: &output, remaining: 5}
	if n, err := writer.Write([]byte("1234")); n != 4 || err != nil {
		t.Fatalf("first Write() = (%d, %v)", n, err)
	}
	if n, err := writer.Write([]byte("56")); n != 0 || err == nil {
		t.Fatalf("overflow Write() = (%d, %v), want size error", n, err)
	}
	if output.String() != "1234" {
		t.Fatalf("output exceeded bound: %q", output.String())
	}
}
