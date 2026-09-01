package storage

import (
	"context"
	"sync"

	maascore "github.com/netcracker/qubership-core-lib-go-maas-core/v3"
	"github.com/netcracker/qubership-core-lib-go-maas-client/v3/classifier"
	maaskafka "github.com/netcracker/qubership-core-lib-go-maas-client/v3/kafka"
)

// MaasKafka exercises the MaaS control plane, not the broker. maas-service keeps its state in
// PostgreSQL, so a leader change there surfaces as 405 from maas-service and 500 from maas-agent.
type MaasKafka struct {
	mu         sync.Mutex
	heldClient maaskafka.MaasClient
}

func NewMaasKafka() *MaasKafka {
	return &MaasKafka{}
}

func (p *MaasKafka) Type() string {
	return "maas-kafka"
}

func (p *MaasKafka) Init(ctx context.Context) error {
	_, err := p.client(PerCall).GetOrCreateTopic(ctx, probeClassifier("probe"))
	return err
}

// WriteAndRead performs one get-or-create: idempotent, and it goes through maas-agent to
// maas-service to its database, which is the whole path a leader change disturbs.
func (p *MaasKafka) WriteAndRead(ctx context.Context, mode HandleMode, key, value string) (string, error) {
	address, err := p.client(mode).GetOrCreateTopic(ctx, probeClassifier(key))
	if err != nil {
		return "", err
	}
	if address == nil {
		return "", nil
	}
	return value, nil
}

func (p *MaasKafka) Read(ctx context.Context, mode HandleMode, key string) (string, error) {
	topic, err := p.client(mode).GetTopic(ctx, probeClassifier(key))
	if err != nil || topic == nil {
		return "", err
	}
	return topic.TopicName, nil
}

func (p *MaasKafka) ReleaseHeldHandle() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.heldClient = nil
}

func (p *MaasKafka) Diagnostics() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	return map[string]any{"holdsClient": p.heldClient != nil}
}

func (p *MaasKafka) client(mode HandleMode) maaskafka.MaasClient {
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

// probeClassifier gives a stable name per key, so repeated operations hit the same topic
// rather than creating a new one every time.
func probeClassifier(key string) classifier.Keys {
	return classifier.New("storage-probe-" + key)
}
