package workload

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-storage-test-service-go/storage"
)

var logger = logging.GetLogger("workload")

// Enough history for a multi-cycle leak scenario without letting the application grow unbounded.
const maxRetainedOutcomes = 20000

// Runner drives background load against one storage. The timeline is recorded in-process, next to
// the library under test, so a stalling port-forward cannot be mistaken for a storage failure.
type Runner struct {
	probes map[string]storage.Probe

	mu          sync.Mutex
	cancel      context.CancelFunc
	done        chan struct{}
	storageType string
	handleMode  storage.HandleMode
	startedAt   int64

	outcomeMu         sync.Mutex
	outcomes          []Outcome
	sequence          int64
	succeeded         int64
	failed            int64
	maxDurationMillis int64
}

func NewRunner(probes ...storage.Probe) *Runner {
	byType := make(map[string]storage.Probe, len(probes))
	for _, probe := range probes {
		byType[probe.Type()] = probe
	}
	return &Runner{probes: byType}
}

func (r *Runner) Probe(storageType string) (storage.Probe, error) {
	probe, ok := r.probes[storageType]
	if !ok {
		return nil, fmt.Errorf("unknown storage type: %s, known: %s", storageType, r.knownTypes())
	}
	return probe, nil
}

func (r *Runner) Probes() []storage.Probe {
	probes := make([]storage.Probe, 0, len(r.probes))
	for _, probe := range r.probes {
		probes = append(probes, probe)
	}
	return probes
}

func (r *Runner) Start(ctx context.Context, storageType string, mode storage.HandleMode, operationsPerSecond int) error {
	probe, err := r.Probe(storageType)
	if err != nil {
		return err
	}
	r.Stop()
	if err = probe.Init(ctx); err != nil {
		return err
	}

	r.reset()
	period := time.Second / time.Duration(max(1, operationsPerSecond))
	if period < time.Millisecond {
		period = time.Millisecond
	}

	loopCtx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})

	r.mu.Lock()
	r.cancel = cancel
	r.done = done
	r.storageType = storageType
	r.handleMode = mode
	r.startedAt = nowMillis()
	r.mu.Unlock()

	go r.loop(loopCtx, done, probe, mode, period)
	logger.Info("Workload started: storage=%s, handleMode=%s, period=%s", storageType, mode, period)
	return nil
}

func (r *Runner) Stop() {
	r.mu.Lock()
	cancel, done, storageType := r.cancel, r.done, r.storageType
	r.cancel, r.done = nil, nil
	r.mu.Unlock()

	if cancel != nil {
		cancel()
		<-done
	}
	if storageType != "" {
		if probe, err := r.Probe(storageType); err == nil {
			probe.ReleaseHeldHandle()
		}
	}
}

func (r *Runner) Running() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.cancel != nil
}

// loop uses a fixed delay rather than a fixed rate: a slow operation must not queue a burst
// behind it, which would show up as a latency spike the storage never caused.
func (r *Runner) loop(ctx context.Context, done chan struct{}, probe storage.Probe,
	mode storage.HandleMode, period time.Duration) {
	defer close(done)
	for {
		r.runOnce(ctx, probe, mode)
		select {
		case <-ctx.Done():
			return
		case <-time.After(period):
		}
	}
}

func (r *Runner) runOnce(ctx context.Context, probe storage.Probe, mode storage.HandleMode) {
	r.outcomeMu.Lock()
	r.sequence++
	sequence := r.sequence
	r.outcomeMu.Unlock()

	startedAt := nowMillis()
	started := time.Now()

	key := "probe-" + strconv.FormatInt(sequence%16, 10)
	value := strconv.FormatInt(sequence, 10)

	read, err := probe.WriteAndRead(ctx, mode, key, value)
	if err == nil && read != value {
		err = fmt.Errorf("wrote '%s' but read back '%s'", value, read)
	}
	if err != nil && ctx.Err() != nil {
		// the workload was stopped mid-operation; that is not a storage failure
		return
	}
	r.record(newOutcome(sequence, startedAt, time.Since(started), err))
}

func (r *Runner) record(outcome Outcome) {
	r.outcomeMu.Lock()
	defer r.outcomeMu.Unlock()

	r.outcomes = append(r.outcomes, outcome)
	if len(r.outcomes) > maxRetainedOutcomes {
		r.outcomes = r.outcomes[len(r.outcomes)-maxRetainedOutcomes:]
	}
	if outcome.DurationMillis > r.maxDurationMillis {
		r.maxDurationMillis = outcome.DurationMillis
	}
	if outcome.Success {
		r.succeeded++
	} else {
		r.failed++
	}
}

func (r *Runner) reset() {
	r.outcomeMu.Lock()
	defer r.outcomeMu.Unlock()
	r.outcomes = nil
	r.sequence = 0
	r.succeeded = 0
	r.failed = 0
	r.maxDurationMillis = 0
}

func (r *Runner) Stats() Stats {
	r.mu.Lock()
	stats := Stats{
		Running:         r.cancel != nil,
		Storage:         r.storageType,
		HandleMode:      string(r.handleMode),
		StartedAtMillis: r.startedAt,
	}
	r.mu.Unlock()

	r.outcomeMu.Lock()
	defer r.outcomeMu.Unlock()

	stats.Total = r.succeeded + r.failed
	stats.Succeeded = r.succeeded
	stats.Failed = r.failed
	stats.MaxDurationMillis = r.maxDurationMillis
	stats.Outcomes = append([]Outcome(nil), r.outcomes...)

	for _, outcome := range stats.Outcomes {
		if outcome.Success {
			continue
		}
		at := outcome.StartedAtMillis
		if stats.FirstFailureAtMillis == nil {
			stats.FirstFailureAtMillis = &at
		}
		stats.LastFailureAtMillis = &at
	}
	if stats.LastFailureAtMillis != nil {
		for _, outcome := range stats.Outcomes {
			if outcome.Success && outcome.StartedAtMillis > *stats.LastFailureAtMillis {
				at := outcome.StartedAtMillis
				stats.FirstSuccessAfterLastFailureMillis = &at
				break
			}
		}
	}
	return stats
}

func (r *Runner) knownTypes() string {
	types := make([]string, 0, len(r.probes))
	for storageType := range r.probes {
		types = append(types, storageType)
	}
	sort.Strings(types)
	return fmt.Sprint(types)
}

func newOutcome(sequence, startedAtMillis int64, elapsed time.Duration, err error) Outcome {
	outcome := Outcome{
		Sequence:        sequence,
		StartedAtMillis: startedAtMillis,
		DurationMillis:  elapsed.Milliseconds(),
		Success:         err == nil,
	}
	if err != nil {
		// the innermost error classifies the failure; the wrapper rarely says anything useful
		outcome.ErrorClass = fmt.Sprintf("%T", rootCause(err))
		outcome.ErrorMessage = err.Error()
	}
	return outcome
}

func rootCause(err error) error {
	for {
		unwrapped := errors.Unwrap(err)
		if unwrapped == nil {
			return err
		}
		err = unwrapped
	}
}

func nowMillis() int64 {
	return time.Now().UnixMilli()
}
