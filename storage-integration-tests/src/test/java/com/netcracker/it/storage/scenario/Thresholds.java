package com.netcracker.it.storage.scenario;

import java.time.Duration;

/**
 * What "passes" means, per storage, in one reviewable place.
 *
 * <p>Deliberately not zero-error: a leader change will produce errors. The suite asserts that the
 * failure is bounded, recovers, and is correctly classified — not that it never happened.
 */
public record Thresholds(
        /* A successful operation must occur within this long after the fault clears. */
        Duration recovery,
        /* No single operation may take longer than this; a hung call is a hard failure. */
        Duration maxOperation,
        /* Tolerance for the leak scenario, as a fraction of the baseline. */
        double leakTolerance,
        /* Fault cycles the leak scenario performs. A leak is monotonic, so a handful shows it. */
        int leakCycles) {

    /**
     * Patroni promotes a replica in a few seconds; 30s leaves room for the client to notice, drop
     * its cached connection and rebuild. maxOperation is well under the recovery window on purpose
     * — an operation that outlives it is hanging, not slow.
     */
    public static Thresholds postgresql() {
        return new Thresholds(Duration.ofSeconds(30), Duration.ofSeconds(20), 0.25, 5);
    }

    /**
     * The MaaS client retries a call for up to maas.http.retry.max-total-duration-ms (60s by
     * default) before failing, so both numbers sit above the database election itself.
     */
    public static Thresholds maas() {
        return new Thresholds(Duration.ofSeconds(90), Duration.ofSeconds(65), 0.25, 5);
    }

    /**
     * Losing one instance of maas-agent is a transport error on an open connection, not a wait for
     * an election, so the client should be back on the next call. Fewer cycles than a database
     * failover: a pod restart is quick, and twenty of them is time spent, not coverage gained.
     */
    public static Thresholds maasAgent() {
        return new Thresholds(Duration.ofSeconds(45), Duration.ofSeconds(30), 0.25, 5);
    }

    /**
     * Losing the broker takes the whole instance down, so recovery is the pod returning, the topic
     * being reconciled and the producer refreshing metadata. maxOperation matches the producer
     * delivery timeout.
     */
    public static Thresholds kafka() {
        return new Thresholds(Duration.ofSeconds(60), Duration.ofSeconds(25), 0.25, 5);
    }
}
