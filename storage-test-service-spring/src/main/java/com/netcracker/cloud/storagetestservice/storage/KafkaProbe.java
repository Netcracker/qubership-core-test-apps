package com.netcracker.cloud.storagetestservice.storage;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicCreateOptions;
import com.netcracker.cloud.storagetestservice.workload.HandleMode;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Kafka data plane: a topic obtained from MaaS, then produce and consume against it.
 *
 * <p>An operation is one produce that waits for the broker acknowledgement. A background consumer
 * records what actually arrived, so the suite can tell a slow delivery from a lost message.
 */
@Component
public class KafkaProbe implements StorageProbe {

    private static final Logger log = LoggerFactory.getLogger(KafkaProbe.class);

    private static final String PROTOCOL = "PLAINTEXT";
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(500);

    private final MaaSAPIClient maas;

    private volatile TopicAddress topic;
    private volatile KafkaProducer<String, String> producer;
    private volatile Thread consumerThread;
    private volatile boolean consuming;

    private final Set<String> received = ConcurrentHashMap.newKeySet();
    private final AtomicLong sent = new AtomicLong();

    public KafkaProbe(MaaSAPIClient maas) {
        this.maas = maas;
    }

    @Override
    public String type() {
        return "kafka";
    }

    @Override
    public synchronized void init() {
        topic = maas.getKafkaClient().getOrCreateTopic(new Classifier("storage-probe"), TopicCreateOptions.DEFAULTS);
        if (producer == null) {
            producer = new KafkaProducer<>(producerConfig());
        }
        if (consumerThread == null) {
            startConsumer();
        }
    }

    /** One operation: publish and wait for the acknowledgement. */
    @Override
    public String writeAndRead(HandleMode handleMode, String key, String value) {
        if (handleMode == HandleMode.PER_CALL) {
            // a fresh producer per operation resolves the brokers again, the way a short-lived
            // caller would
            try (KafkaProducer<String, String> perCall = new KafkaProducer<>(producerConfig())) {
                send(perCall, key, value);
            }
        } else {
            send(producer, key, value);
        }
        return value;
    }

    private void send(KafkaProducer<String, String> target, String key, String value) {
        try {
            target.send(new ProducerRecord<>(topic.getTopicName(), key, value))
                    .get(ACK_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            sent.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the broker acknowledgement", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish to " + topic.getTopicName(), e);
        }
    }

    /** Whether a value published earlier has arrived; null keeps the workload's contract. */
    @Override
    public String read(HandleMode handleMode, String key) {
        return received.contains(key) ? key : null;
    }

    @Override
    public synchronized void releaseHeldHandle() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
            producer = null;
        }
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("topic", topic == null ? null : topic.getTopicName());
        diagnostics.put("sent", sent.get());
        diagnostics.put("received", received.size());
        diagnostics.put("holdsProducer", producer != null);
        return diagnostics;
    }

    private Map<String, Object> connectionProperties() {
        Map<String, Object> properties = new HashMap<>();
        topic.formatConnectionProperties().ifPresentOrElse(
                properties::putAll,
                () -> properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        topic.getBoostrapServers(PROTOCOL)));
        return properties;
    }

    private Map<String, Object> producerConfig() {
        Map<String, Object> config = connectionProperties();
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // wait for the leader, so a lost write surfaces as an error instead of silence
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        // bound every stage: a hung publish would be indistinguishable from a slow one
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) ACK_TIMEOUT.toMillis());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return config;
    }

    private void startConsumer() {
        consuming = true;
        consumerThread = new Thread(this::consumeLoop, "storage-kafka-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    private void consumeLoop() {
        Map<String, Object> config = connectionProperties();
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "storage-probe-" + topic.getTopicName());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic.getTopicName()));
            while (consuming) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(POLL);
                    for (ConsumerRecord<String, String> record : records) {
                        received.add(record.key());
                    }
                } catch (Exception e) {
                    // a broker going away surfaces here; the consumer reconnects on the next poll
                    log.warn("Consumer poll failed, will retry", e);
                }
            }
        }
    }

    @PreDestroy
    public synchronized void stop() {
        consuming = false;
        releaseHeldHandle();
    }
}
