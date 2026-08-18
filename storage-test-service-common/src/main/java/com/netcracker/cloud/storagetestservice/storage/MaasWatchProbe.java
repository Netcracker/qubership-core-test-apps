package com.netcracker.cloud.storagetestservice.storage;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicCreateOptions;
import com.netcracker.cloud.storagetestservice.workload.HandleMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The watch subscription, which is a long poll held open against maas-agent. Nothing else in the
 * suite covers it, and it is where the client keeps its own connection across a fault rather than
 * opening a new one per call.
 *
 * <p>One operation registers a watch on a name that does not exist yet, creates the topic, and
 * waits for the callback. The names are unique because a watch fires once; the Java client has no
 * delete, so the registrations accumulate and the workload runs slowly on purpose.
 */
public class MaasWatchProbe implements StorageProbe {

    private static final long CALLBACK_TIMEOUT_SECONDS = 30;

    private final MaaSAPIClient maas;
    private final AtomicLong watched = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();

    private volatile KafkaMaaSClient heldClient;

    public MaasWatchProbe(MaaSAPIClient maas) {
        this.maas = maas;
    }

    @Override
    public String type() {
        return "maas-watch";
    }

    @Override
    public void init() {
        // nothing to prepare: every operation subscribes to a name of its own
    }

    /** One operation: subscribe, create the topic, and wait for the notification to arrive. */
    @Override
    public String writeAndRead(HandleMode handleMode, String key, String value) {
        KafkaMaaSClient client = kafkaClient(handleMode);
        String name = "storage-watch-" + watched.incrementAndGet();
        CountDownLatch notified = new CountDownLatch(1);

        client.watchTopicCreate(name, address -> notified.countDown());
        client.getOrCreateTopic(new Classifier(name), TopicCreateOptions.DEFAULTS);

        try {
            if (!notified.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("no watch callback for " + name + " within "
                        + CALLBACK_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the watch callback", e);
        }
        delivered.incrementAndGet();
        return value;
    }

    /** Watches are one-shot, so there is nothing to read back beyond what the callback delivered. */
    @Override
    public String read(HandleMode handleMode, String key) {
        return delivered.get() > 0 ? key : null;
    }

    @Override
    public void releaseHeldHandle() {
        heldClient = null;
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("watched", watched.get());
        diagnostics.put("delivered", delivered.get());
        diagnostics.put("holdsClient", heldClient != null);
        return diagnostics;
    }

    private KafkaMaaSClient kafkaClient(HandleMode handleMode) {
        if (handleMode == HandleMode.PER_CALL) {
            return maas.getKafkaClient();
        }
        if (heldClient == null) {
            synchronized (this) {
                if (heldClient == null) {
                    heldClient = maas.getKafkaClient();
                }
            }
        }
        return heldClient;
    }
}
