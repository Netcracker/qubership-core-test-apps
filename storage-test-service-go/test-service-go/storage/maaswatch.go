package storage

import (
	"context"
	"fmt"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	maascore "github.com/netcracker/qubership-core-lib-go-maas-core/v3"
	"github.com/netcracker/qubership-core-lib-go-maas-client/v3/classifier"
	maaskafka "github.com/netcracker/qubership-core-lib-go-maas-client/v3/kafka"
	maasmodel "github.com/netcracker/qubership-core-lib-go-maas-client/v3/kafka/model"
)

// How long a notification may take before the subscription is considered broken.
const watchCallbackTimeout = 30 * time.Second

// MaasWatch exercises the watch subscription, which is a long poll held open against maas-agent.
//
// One operation subscribes to a name that does not exist yet, creates the topic, and waits for the
// callback. Names are unique because a watch fires once, so the registrations accumulate and the
// workload runs slowly on purpose.
type MaasWatch struct {
	mu         sync.Mutex
	heldClient maaskafka.MaasClient

	watched   atomic.Int64
	delivered atomic.Int64
}

func NewMaasWatch() *MaasWatch {
	return &MaasWatch{}
}

func (p *MaasWatch) Type() string {
	return "maas-watch"
}

func (p *MaasWatch) Init(_ context.Context) error {
	// nothing to prepare: every operation subscribes to a name of its own
	return nil
}

// WriteAndRead subscribes, creates the topic, and waits for the notification to arrive.
func (p *MaasWatch) WriteAndRead(ctx context.Context, mode HandleMode, key, value string) (string, error) {
	client := p.client(mode)
	name := "storage-watch-" + strconv.FormatInt(p.watched.Add(1), 10)

	// the watcher outlives the call only until the callback arrives
	watchCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	notified := make(chan struct{}, 1)
	err := client.WatchTopicCreate(watchCtx, classifier.New(name), func(maasmodel.TopicAddress) {
		select {
		case notified <- struct{}{}:
		default:
		}
	})
	if err != nil {
		return "", fmt.Errorf("failed to watch %s: %w", name, err)
	}

	if _, err = client.GetOrCreateTopic(ctx, classifier.New(name)); err != nil {
		return "", err
	}

	select {
	case <-notified:
		p.delivered.Add(1)
		return value, nil
	case <-time.After(watchCallbackTimeout):
		return "", fmt.Errorf("no watch callback for %s within %s", name, watchCallbackTimeout)
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

// Read has nothing to return beyond what the callbacks delivered: a watch is one-shot.
func (p *MaasWatch) Read(_ context.Context, _ HandleMode, key string) (string, error) {
	if p.delivered.Load() > 0 {
		return key, nil
	}
	return "", nil
}

func (p *MaasWatch) ReleaseHeldHandle() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.heldClient = nil
}

func (p *MaasWatch) Diagnostics() map[string]any {
	p.mu.Lock()
	holdsClient := p.heldClient != nil
	p.mu.Unlock()

	return map[string]any{
		"watched":     p.watched.Load(),
		"delivered":   p.delivered.Load(),
		"holdsClient": holdsClient,
	}
}

func (p *MaasWatch) client(mode HandleMode) maaskafka.MaasClient {
	if mode == PerCall {
		return maascore.NewKafkaClient()
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.heldClient == nil {
		p.heldClient = maascore.NewKafkaClient()
	}
	return p.heldClient
}
