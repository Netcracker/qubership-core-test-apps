package com.netcracker.cloud.storagetestservice.storage;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicCreateOptions;
import com.netcracker.cloud.storagetestservice.workload.HandleMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The watch subscription, which is a long poll the client holds open against maas-agent. Nothing
 * else in the suite covers it, and it is the one place the client keeps a connection across a fault
 * instead of opening a new one per call.
 *
 * <p>An operation therefore never blocks: it creates the watched topic, collects the callback if it
 * has arrived, and arms the next subscription. A callback that never arrives fails the operation
 * once the deadline passes, and the probe re-arms rather than wedging the workload.
 */
public class MaasWatchProbe implements StorageProbe {

    /** How long a notification may take before the subscription is considered broken. */
    private static final long CALLBACK_TIMEOUT_MILLIS = 30_000;

    private final MaaSAPIClient maas;
    private final AtomicLong names = new AtomicLong();
    private final AtomicLong watched = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();

    private volatile KafkaMaaSClient client;
    private volatile Subscription current;

    public MaasWatchProbe(MaaSAPIClient maas) {
        this.maas = maas;
    }

    /** One armed watch: the name nobody else uses, and the latch its callback counts down. */
    private record Subscription(String name, CountDownLatch notified, long armedAtMillis) {
    }

    @Override
    public String type() {
        return "maas-watch";
    }

    @Override
    public void init() {
        if (client == null) {
            client = maas.getKafkaClient();
        }
        arm();
    }

    /**
     * Creates the watched topic, then reports whether the callback for it has arrived. The handle
     * mode is not honoured: a subscription is long-held by nature, and a per-call client would open
     * a poll per operation.
     */
    @Override
    public String writeAndRead(HandleMode handleMode, String key, String value) {
        Subscription subscription = current;
        client.getOrCreateTopic(new Classifier(subscription.name()), TopicCreateOptions.DEFAULTS);

        if (subscription.notified().getCount() == 0) {
            delivered.incrementAndGet();
            arm();
            return value;
        }
        if (System.currentTimeMillis() - subscription.armedAtMillis() > CALLBACK_TIMEOUT_MILLIS) {
            arm();
            throw new IllegalStateException("no watch callback for " + subscription.name()
                    + " within " + CALLBACK_TIMEOUT_MILLIS + "ms");
        }
        return value;
    }

    /** Watches are one-shot, so there is nothing to read back beyond what the callbacks delivered. */
    @Override
    public String read(HandleMode handleMode, String key) {
        return delivered.get() > 0 ? key : null;
    }

    @Override
    public void releaseHeldHandle() {
        client = null;
        current = null;
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("watched", watched.get());
        diagnostics.put("delivered", delivered.get());
        diagnostics.put("holdsClient", client != null);
        return diagnostics;
    }

    /** Subscribes to a name no topic carries yet, so the callback can only come from our create. */
    private void arm() {
        CountDownLatch notified = new CountDownLatch(1);
        String name = "storage-watch-" + names.incrementAndGet();
        current = new Subscription(name, notified, System.currentTimeMillis());
        watched.incrementAndGet();
        client.watchTopicCreate(name, address -> notified.countDown());
    }
}
