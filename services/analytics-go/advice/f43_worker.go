package advice

import (
	"context"
	"errors"
	"sync"
	"time"
)

type F43Worker struct {
	core         F43Core
	pollInterval time.Duration
	processMu    sync.Mutex
}

func NewF43Worker(core F43Core, pollInterval time.Duration) (*F43Worker, error) {
	if core == nil {
		return nil, errors.New("F43 worker requires a Core client")
	}
	if pollInterval <= 0 {
		return nil, errors.New("F43 worker poll interval must be positive")
	}
	return &F43Worker{core: core, pollInterval: pollInterval}, nil
}

func (worker *F43Worker) ProcessOne(ctx context.Context) (bool, error) {
	worker.processMu.Lock()
	defer worker.processMu.Unlock()
	job, err := worker.core.Claim(ctx)
	if err != nil {
		return false, err
	}
	if job == nil {
		return false, nil
	}
	result := F43JobResult{
		LeaseToken: job.LeaseToken, InputWatermark: job.InputWatermark,
		AlgorithmVersion: job.AlgorithmVersion,
	}
	report, err := BuildF43Report(job.Input)
	if err != nil {
		result.ErrorCode = "calculation_failed"
	} else {
		result.Report = &report
	}
	if err := worker.core.Complete(ctx, job.ID, result); err != nil {
		return true, err
	}
	return true, nil
}

func (worker *F43Worker) Run(ctx context.Context) error {
	for {
		_, err := worker.ProcessOne(ctx)
		if err != nil && ctx.Err() != nil {
			return ctx.Err()
		}
		if err != nil {
			if err := waitF43(ctx, worker.pollInterval); err != nil {
				return err
			}
			continue
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err := waitF43(ctx, worker.pollInterval); err != nil {
			return err
		}
	}
}

func waitF43(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
