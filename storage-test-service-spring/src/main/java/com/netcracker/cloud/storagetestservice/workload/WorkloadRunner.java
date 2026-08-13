package com.netcracker.cloud.storagetestservice.workload;

import com.netcracker.cloud.storagetestservice.storage.StorageProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Background load against one storage. The timeline is recorded in-cluster, next to the library
 * under test, so a stalling port-forward cannot be mistaken for a storage failure.
 */
@Component
public class WorkloadRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkloadRunner.class);

    /** Enough history for a multi-cycle leak scenario without letting the app grow without bound. */
    private static final int MAX_RETAINED_OUTCOMES = 20_000;

    private final Map<String, StorageProbe> probes;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    private final ConcurrentLinkedQueue<OperationOutcome> outcomes = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong maxDurationMillis = new AtomicLong();

    private volatile String storage;
    private volatile HandleMode handleMode = HandleMode.PER_CALL;
    private volatile long startedAtMillis;

    public WorkloadRunner(List<StorageProbe> probes) {
        this.probes = probes.stream().collect(Collectors.toMap(StorageProbe::type, probe -> probe));
    }

    public StorageProbe probe(String storageType) {
        StorageProbe probe = probes.get(storageType);
        if (probe == null) {
            throw new IllegalArgumentException("unknown storage type: " + storageType
                    + ", known: " + probes.keySet());
        }
        return probe;
    }

    public synchronized void start(String storageType, HandleMode mode, int operationsPerSecond) {
        stop();
        StorageProbe probe = probe(storageType);
        probe.init();

        reset();
        this.storage = storageType;
        this.handleMode = mode;
        this.startedAtMillis = System.currentTimeMillis();

        long periodMillis = Math.max(1, 1000L / Math.max(1, operationsPerSecond));
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "storage-workload");
            thread.setDaemon(true);
            return thread;
        });
        // fixed delay, not fixed rate: a slow operation must not queue a burst behind it, which
        // would show up as a latency spike the storage never caused
        task = executor.scheduleWithFixedDelay(this::runOnce, 0, periodMillis, TimeUnit.MILLISECONDS);
        log.info("Workload started: storage={}, handleMode={}, period={}ms", storageType, mode, periodMillis);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (storage != null) {
            probe(storage).releaseHeldHandle();
        }
    }

    public boolean isRunning() {
        return task != null && !task.isCancelled();
    }

    private void runOnce() {
        long seq = sequence.incrementAndGet();
        long startedAt = System.currentTimeMillis();
        long startedNanos = System.nanoTime();
        try {
            String key = "probe-" + (seq % 16);
            String value = Long.toString(seq);
            String read = probe(storage).writeAndRead(handleMode, key, value);
            if (!value.equals(read)) {
                throw new IllegalStateException("wrote '" + value + "' but read back '" + read + "'");
            }
            record(OperationOutcome.ok(seq, startedAt, elapsedMillis(startedNanos)));
            succeeded.incrementAndGet();
        } catch (Exception e) {
            record(OperationOutcome.failed(seq, startedAt, elapsedMillis(startedNanos), e));
            failed.incrementAndGet();
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private void record(OperationOutcome outcome) {
        outcomes.add(outcome);
        maxDurationMillis.accumulateAndGet(outcome.durationMillis(), Math::max);
        while (outcomes.size() > MAX_RETAINED_OUTCOMES) {
            outcomes.poll();
        }
    }

    private void reset() {
        outcomes.clear();
        sequence.set(0);
        succeeded.set(0);
        failed.set(0);
        maxDurationMillis.set(0);
    }

    public WorkloadStats stats() {
        List<OperationOutcome> snapshot = List.copyOf(outcomes);
        Long firstFailure = null;
        Long lastFailure = null;
        for (OperationOutcome outcome : snapshot) {
            if (!outcome.success()) {
                if (firstFailure == null) {
                    firstFailure = outcome.startedAtMillis();
                }
                lastFailure = outcome.startedAtMillis();
            }
        }
        Long firstSuccessAfterLastFailure = null;
        if (lastFailure != null) {
            for (OperationOutcome outcome : snapshot) {
                if (outcome.success() && outcome.startedAtMillis() > lastFailure) {
                    firstSuccessAfterLastFailure = outcome.startedAtMillis();
                    break;
                }
            }
        }
        return new WorkloadStats(isRunning(), storage, handleMode, startedAtMillis,
                sequence.get(), succeeded.get(), failed.get(),
                firstFailure, lastFailure, firstSuccessAfterLastFailure,
                maxDurationMillis.get(), snapshot);
    }
}
