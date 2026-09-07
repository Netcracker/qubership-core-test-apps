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
     * Both clients bound one call, retries included, to a minute before giving up, so both numbers
     * sit above the database election itself. A vhost and a watch travel the same path as a topic,
     * so all three MaaS profiles share this allowance. An operation that outlives maxOperation is
     * hanging rather than slow, which is a hard failure on either platform.
     */
    public static Thresholds maas() {
        return new Thresholds(Duration.ofSeconds(90), Duration.ofSeconds(65), 0.25, 5);
    }

    /**
     * Losing one instance of maas-agent is a transport error on an open connection, not a wait for
     * an election, so the client should be back on the next call.
     */
    public static Thresholds maasAgent() {
        return new Thresholds(Duration.ofSeconds(45), Duration.ofSeconds(70), 0.25, 5);
    }
}
