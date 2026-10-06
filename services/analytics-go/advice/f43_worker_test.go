package advice

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type fakeF43Core struct {
	claims      []*F43LeasedJob
	results     []F43JobResult
	claimErr    error
	completeErr error
}

func (fake *fakeF43Core) Claim(context.Context) (*F43LeasedJob, error) {
	if fake.claimErr != nil {
		return nil, fake.claimErr
	}
	if len(fake.claims) == 0 {
		return nil, nil
	}
	job := fake.claims[0]
	fake.claims = fake.claims[1:]
	return job, nil
}

func (fake *fakeF43Core) Complete(_ context.Context, _ string, result F43JobResult) error {
	fake.results = append(fake.results, result)
	return fake.completeErr
}

func TestF43WorkerReturnsWhenCoreHasNoEligibleJob(t *testing.T) {
	core := &fakeF43Core{}
	worker, err := NewF43Worker(core, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err != nil || processed {
		t.Fatalf("ProcessOne() = (%v, %v), want (false, nil)", processed, err)
	}
}

func TestF43WorkerCalculatesAndSubmitsLeasedReport(t *testing.T) {
	core := &fakeF43Core{claims: []*F43LeasedJob{{ID: "job-1", LeaseToken: "lease-1", Input: f43TestRequest("7", "2026-01-01T00:00:00Z", nil)}}}
	worker, err := NewF43Worker(core, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err != nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want processed", processed, err)
	}
	if len(core.results) != 1 {
		t.Fatalf("results = %d, want 1", len(core.results))
	}
	result := core.results[0]
	if result.LeaseToken != "lease-1" || result.Report == nil || result.Report.InputWatermark != "7" || result.ErrorCode != "" {
		t.Fatalf("unexpected result: %+v", result)
	}
}

func TestF43WorkerDeliversCalculationFailureForCoreRetry(t *testing.T) {
	input := f43TestRequest("7", "2026-01-01T00:00:00Z", nil)
	input.TimeZone = "Invalid/Zone"
	core := &fakeF43Core{claims: []*F43LeasedJob{{ID: "job-2", LeaseToken: "lease-2", Input: input}}}
	worker, err := NewF43Worker(core, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	processed, err := worker.ProcessOne(context.Background())
	if err != nil || !processed {
		t.Fatalf("ProcessOne() = (%v, %v), want failure delivered", processed, err)
	}
	if len(core.results) != 1 || core.results[0].ErrorCode != "calculation_failed" || core.results[0].Report != nil {
		t.Fatalf("expected typed calculation failure, got %+v", core.results)
	}
}

func TestF43WorkerReturnsCoreCompletionFailure(t *testing.T) {
	core := &fakeF43Core{
		claims:      []*F43LeasedJob{{ID: "job-3", LeaseToken: "lease-3", Input: f43TestRequest("8", "2026-01-01T00:00:00Z", nil)}},
		completeErr: errors.New("core unavailable"),
	}
	worker, err := NewF43Worker(core, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := worker.ProcessOne(context.Background()); err == nil {
		t.Fatal("expected completion failure")
	}
}

type transientClaimCore struct {
	mu    sync.Mutex
	calls int
}

func (core *transientClaimCore) Claim(context.Context) (*F43LeasedJob, error) {
	core.mu.Lock()
	defer core.mu.Unlock()
	core.calls++
	return nil, errors.New("temporary Core failure")
}

func (*transientClaimCore) Complete(context.Context, string, F43JobResult) error { return nil }

func TestF43WorkerKeepsPollingAfterTransientCoreFailure(t *testing.T) {
	core := &transientClaimCore{}
	worker, err := NewF43Worker(core, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := worker.Run(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Run() error = %v", err)
	}
	core.mu.Lock()
	calls := core.calls
	core.mu.Unlock()
	if calls < 2 {
		t.Fatalf("claim calls = %d, want worker retry after transient failure", calls)
	}
}

type serializedCore struct {
	mu      sync.Mutex
	claims  int
	started chan struct{}
	release chan struct{}
}

func (core *serializedCore) Claim(context.Context) (*F43LeasedJob, error) {
	core.mu.Lock()
	defer core.mu.Unlock()
	core.claims++
	if core.claims > 1 {
		return nil, nil
	}
	return &F43LeasedJob{ID: "one", LeaseToken: "lease", Input: f43TestRequest("1", "2026-01-01T00:00:00Z", nil)}, nil
}

func (core *serializedCore) Complete(context.Context, string, F43JobResult) error {
	close(core.started)
	<-core.release
	return nil
}

func TestF43WorkerSerializesProcessingWithinOneInstance(t *testing.T) {
	core := &serializedCore{started: make(chan struct{}), release: make(chan struct{})}
	worker, err := NewF43Worker(core, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	firstDone := make(chan error, 1)
	go func() { _, processErr := worker.ProcessOne(context.Background()); firstDone <- processErr }()
	select {
	case <-core.started:
	case <-time.After(time.Second):
		t.Fatal("first job did not reach result delivery")
	}
	secondDone := make(chan error, 1)
	go func() { _, processErr := worker.ProcessOne(context.Background()); secondDone <- processErr }()
	time.Sleep(10 * time.Millisecond)
	core.mu.Lock()
	claims := core.claims
	core.mu.Unlock()
	if claims != 1 {
		t.Fatalf("Core claims during first completion = %d, want 1", claims)
	}
	close(core.release)
	if err := <-firstDone; err != nil {
		t.Fatal(err)
	}
	if err := <-secondDone; err != nil {
		t.Fatal(err)
	}
	core.mu.Lock()
	claims = core.claims
	core.mu.Unlock()
	if claims != 2 {
		t.Fatalf("total claims = %d, want serialized second poll", claims)
	}
}

func TestF43WorkerRunStopsDuringIdlePoll(t *testing.T) {
	worker, err := NewF43Worker(&fakeF43Core{}, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if err := worker.Run(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Run() error = %v", err)
	}
}
