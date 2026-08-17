package storage

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	maascore "github.com/netcracker/qubership-core-lib-go-maas-core/v3"
	"github.com/netcracker/qubership-core-lib-go-maas-client/v3/classifier"
	maasmodel "github.com/netcracker/qubership-core-lib-go-maas-client/v3/kafka/model"
	segmentio "github.com/netcracker/qubership-core-lib-go-maas-segmentio/v3"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	kafkago "github.com/segmentio/kafka-go"
)

var logger = logging.GetLogger("storage-probe")

const (
	// A publish that has not been acknowledged by then is treated as hung, not as slow.
	ackTimeout = 20 * time.Second
	// How long the reader waits before trying again after a failed read.
	readRetryDelay = 500 * time.Millisecond
)

// Kafka is the data plane: a topic obtained from MaaS, then produce and consume against it.
// One operation is a publish that waits for the broker acknowledgement, and a background reader
// records what actually arrived, so a slow delivery is distinguishable from a lost message.
type Kafka struct {
	mu     sync.Mutex
	topic  *maasmodel.TopicAddress
	writer *kafkago.Writer
	reader *kafkago.Reader
	cancel context.CancelFunc

	receivedMu sync.RWMutex
	received   map[string]struct{}

	sent atomic.Int64
}

func NewKafka() *Kafka {
	return &Kafka{received: make(map[string]struct{})}
}

func (p *Kafka) Type() string {
	return "kafka"
}

func (p *Kafka) Init(ctx context.Context) error {
	topic, err := maascore.NewKafkaClient().GetOrCreateTopic(ctx, classifier.New("storage-probe"))
	if err != nil {
		return fmt.Errorf("failed to obtain the probe topic: %w", err)
	}

	p.mu.Lock()
	defer p.mu.Unlock()
	p.topic = topic
	if p.writer == nil {
		if p.writer, err = newWriter(topic); err != nil {
			return err
		}
	}
	if p.reader == nil {
		return p.startReader(topic)
	}
	return nil
}

// WriteAndRead publishes one message and waits for the acknowledgement.
func (p *Kafka) WriteAndRead(ctx context.Context, mode HandleMode, key, value string) (string, error) {
	topic, held := p.handles()
	if topic == nil {
		return "", errors.New("the probe is not initialised")
	}

	writer := held
	if mode == PerCall {
		// a fresh writer per operation resolves the brokers again, the way a short-lived
		// caller would
		perCall, err := newWriter(topic)
		if err != nil {
			return "", err
		}
		defer func() {
			if closeErr := perCall.Close(); closeErr != nil {
				logger.Warn("Failed to close the per-call writer: %v", closeErr)
			}
		}()
		writer = perCall
	}
	if writer == nil {
		return "", errors.New("the probe holds no writer")
	}

	if err := p.send(ctx, writer, key, value); err != nil {
		return "", err
	}
	return value, nil
}

func (p *Kafka) send(ctx context.Context, writer *kafkago.Writer, key, value string) error {
	sendCtx, cancel := context.WithTimeout(ctx, ackTimeout)
	defer cancel()

	message := kafkago.Message{Key: []byte(key), Value: []byte(value)}
	if err := writer.WriteMessages(sendCtx, message); err != nil {
		return fmt.Errorf("failed to publish to %s: %w", writer.Topic, err)
	}
	p.sent.Add(1)
	return nil
}

// Read reports whether a value published earlier has arrived.
func (p *Kafka) Read(_ context.Context, _ HandleMode, key string) (string, error) {
	p.receivedMu.RLock()
	defer p.receivedMu.RUnlock()
	if _, ok := p.received[key]; ok {
		return key, nil
	}
	return "", nil
}

func (p *Kafka) ReleaseHeldHandle() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.writer != nil {
		if err := p.writer.Close(); err != nil {
			logger.Warn("Failed to close the writer: %v", err)
		}
		p.writer = nil
	}
}

func (p *Kafka) Diagnostics() map[string]any {
	p.mu.Lock()
	topicName := ""
	if p.topic != nil {
		topicName = p.topic.TopicName
	}
	holdsWriter := p.writer != nil
	p.mu.Unlock()

	p.receivedMu.RLock()
	received := len(p.received)
	p.receivedMu.RUnlock()

	return map[string]any{
		"topic":       topicName,
		"sent":        p.sent.Load(),
		"received":    received,
		"holdsWriter": holdsWriter,
	}
}

// Close stops the reader and releases the writer; called on shutdown.
func (p *Kafka) Close() {
	p.mu.Lock()
	if p.cancel != nil {
		p.cancel()
		p.cancel = nil
	}
	reader := p.reader
	p.reader = nil
	p.mu.Unlock()

	if reader != nil {
		if err := reader.Close(); err != nil {
			logger.Warn("Failed to close the reader: %v", err)
		}
	}
	p.ReleaseHeldHandle()
}

func (p *Kafka) handles() (*maasmodel.TopicAddress, *kafkago.Writer) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.topic, p.writer
}

// startReader must be called with the lock held.
func (p *Kafka) startReader(topic *maasmodel.TopicAddress) error {
	config, err := segmentio.NewReaderConfig(*topic, "storage-probe-"+topic.TopicName)
	if err != nil {
		return fmt.Errorf("failed to build the reader: %w", err)
	}
	config.StartOffset = kafkago.FirstOffset

	reader := kafkago.NewReader(*config)
	ctx, cancel := context.WithCancel(context.Background())
	p.reader = reader
	p.cancel = cancel
	go p.consume(ctx, reader)
	return nil
}

func (p *Kafka) consume(ctx context.Context, reader *kafkago.Reader) {
	for {
		message, err := reader.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			// a broker going away surfaces here; the reader reconnects on the next read
			logger.Warn("Read failed, will retry: %v", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(readRetryDelay):
			}
			continue
		}
		p.receivedMu.Lock()
		p.received[string(message.Key)] = struct{}{}
		p.receivedMu.Unlock()
	}
}

func newWriter(topic *maasmodel.TopicAddress) (*kafkago.Writer, error) {
	writer, err := segmentio.NewWriter(*topic)
	if err != nil {
		return nil, fmt.Errorf("failed to build the writer: %w", err)
	}
	// synchronous, so a failed publish surfaces as an error on the operation that caused it
	writer.Async = false
	writer.BatchTimeout = 10 * time.Millisecond
	return writer, nil
}
