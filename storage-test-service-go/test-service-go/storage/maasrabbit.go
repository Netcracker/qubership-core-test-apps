package storage

import (
	"context"
	"sync"

	maascore "github.com/netcracker/qubership-core-lib-go-maas-core/v3"
	"github.com/netcracker/qubership-core-lib-go-maas-client/v3/rabbit"
)

// MaasRabbit is the other half of MaaS. A vhost is obtained exactly the way a topic is, through
// maas-agent to maas-service to its database, so the same leader change is visible on this path.
type MaasRabbit struct {
	mu         sync.Mutex
	heldClient rabbit.MaasClient
}

func NewMaasRabbit() *MaasRabbit {
	return &MaasRabbit{}
}

func (p *MaasRabbit) Type() string {
	return "maas-rabbit"
}

func (p *MaasRabbit) Init(ctx context.Context) error {
	_, err := p.client(PerCall).GetOrCreateVhost(ctx, probeClassifier("probe"))
	return err
}

// WriteAndRead performs one get-or-create, idempotent and covering the whole MaaS path.
func (p *MaasRabbit) WriteAndRead(ctx context.Context, mode HandleMode, key, value string) (string, error) {
	vhost, err := p.client(mode).GetOrCreateVhost(ctx, probeClassifier(key))
	if err != nil {
		return "", err
	}
	if vhost == nil {
		return "", nil
	}
	return value, nil
}

func (p *MaasRabbit) Read(ctx context.Context, mode HandleMode, key string) (string, error) {
	config, err := p.client(mode).GetVhost(ctx, probeClassifier(key))
	if err != nil || config == nil {
		return "", err
	}
	return config.Vhost.GetConnectionUri(), nil
}

func (p *MaasRabbit) ReleaseHeldHandle() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.heldClient = nil
}

func (p *MaasRabbit) Diagnostics() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	return map[string]any{"holdsClient": p.heldClient != nil}
}

func (p *MaasRabbit) client(mode HandleMode) rabbit.MaasClient {
	if mode == PerCall {
		return maascore.NewRabbitClient()
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.heldClient == nil {
		p.heldClient = maascore.NewRabbitClient()
	}
	return p.heldClient
}
