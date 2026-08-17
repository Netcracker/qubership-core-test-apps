package storage

import "context"

// HandleMode is how a probe obtains its handle - the two access patterns real services have.
type HandleMode string

const (
	// PerCall resolves a client per operation, which is the path the recovery logic sits on.
	PerCall HandleMode = "PER_CALL"
	// LongHeld resolves once at startup, so recovery must work without asking again.
	LongHeld HandleMode = "LONG_HELD"
)

// Probe is one storage behind a uniform contract; adding a storage is adding an implementation.
type Probe interface {
	// Type is the path segment this probe answers to, for example "maas-kafka".
	Type() string

	// Init acquires whatever the probe needs before the first operation.
	Init(ctx context.Context) error

	// WriteAndRead performs one workload operation and returns what was read back.
	WriteAndRead(ctx context.Context, mode HandleMode, key, value string) (string, error)

	// Read returns a previously written value, or an empty string when the key is absent.
	Read(ctx context.Context, mode HandleMode, key string) (string, error)

	// ReleaseHeldHandle drops whatever the probe holds, so the next operation starts clean.
	ReleaseHeldHandle()

	// Diagnostics is merged into /api/v1/diag.
	Diagnostics() map[string]any
}
