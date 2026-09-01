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
const watchCallbackTimeout = 150 * time.Second

// MaasWatch exercises the watch subscription, which is a long poll the client holds open against
// maas-agent, rather than a connection opened per call.
//
// One subscription is outstanding at a time, on one client that lives for the whole scenario. The
// client restarts its poll on every registration and maas-service delivers each event to a single
// waiting watcher, so a subscription per operation would both lose notifications and pile up polls
// until maas-agent stops answering.
//
// An operation therefore never blocks: it creates the watched topic, collects the callback if it
// has arrived, and arms the next subscription. A callback that never arrives fails the operation
// once the deadline passes, and the probe re-arms rather than wedging the workload.
type MaasWatch struct {
	mu       sync.Mutex
	client   maaskafka.MaasClient
	watchCtx context.Context
	cancel   context.CancelFunc
	current  *subscription

	names     atomic.Int64
	watched   atomic.Int64
	delivered atomic.Int64
}

// subscription is one armed watch: the name nobody else uses, and the channel its callback closes.
type subscription struct {
	name     string
	notified chan struct{}
	armedAt  time.Time
}

func NewMaasWatch() *MaasWatch {
	return &MaasWatch{}
}

func (p *MaasWatch) Type() string {
	return "maas-watch"
}

func (p *MaasWatch) Init(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.client == nil {
		p.client = maascore.NewKafkaClient()
	}
	if p.cancel == nil {
		// the watcher outlives the call that armed it, so it gets a context of its own
		watchCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
		p.watchCtx, p.cancel = watchCtx, cancel
	}
	return p.arm()
}

// WriteAndRead creates the watched topic, then reports whether the callback for it has arrived.
// The handle mode is not honoured: a subscription is long-held by nature, and a per-call client
// would open a poll per operation.
func (p *MaasWatch) WriteAndRead(ctx context.Context, _ HandleMode, key, value string) (string, error) {
	p.mu.Lock()
	client, current := p.client, p.current
	p.mu.Unlock()
	if client == nil || current == nil {
		return "", fmt.Errorf("the watch probe has no armed subscription")
	}

	if _, err := client.GetOrCreateTopic(ctx, classifier.New(current.name)); err != nil {
		return "", err
	}

	select {
	case <-current.notified:
		p.delivered.Add(1)
		return value, p.rearm()
	default:
	}

	if time.Since(current.armedAt) > watchCallbackTimeout {
		if err := p.rearm(); err != nil {
			return "", err
		}
		return "", fmt.Errorf("no watch callback for %s within %s", current.name, watchCallbackTimeout)
	}
	return value, nil
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
	if p.cancel != nil {
		p.cancel()
		p.cancel, p.watchCtx = nil, nil
	}
	p.client = nil
	p.current = nil
}

func (p *MaasWatch) Diagnostics() map[string]any {
	p.mu.Lock()
	holdsClient := p.client != nil
	p.mu.Unlock()

	return map[string]any{
		"watched":     p.watched.Load(),
		"delivered":   p.delivered.Load(),
		"holdsClient": holdsClient,
	}
}

func (p *MaasWatch) rearm() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.arm()
}

// arm subscribes to a name no topic carries yet, so the callback can only come from our create.
// The caller holds the lock.
func (p *MaasWatch) arm() error {
	notified := make(chan struct{})
	name := "storage-watch-" + strconv.FormatInt(p.names.Add(1), 10)
	var once sync.Once
	err := p.client.WatchTopicCreate(p.watchCtx, classifier.New(name), func(maasmodel.TopicAddress) {
		once.Do(func() { close(notified) })
	})
	if err != nil {
		return fmt.Errorf("failed to watch %s: %w", name, err)
	}

	p.current = &subscription{name: name, notified: notified, armedAt: time.Now()}
	p.watched.Add(1)
	return nil
}
