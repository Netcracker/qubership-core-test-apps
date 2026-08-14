package com.netcracker.it.storage.controller;

import java.time.Duration;

/**
 * The faults a scenario can inject, in one vocabulary for every storage. Implementations are a
 * Kubernetes call or an {@code exec} of the storage's own admin CLI.
 */
public interface FaultController {

    /** Abrupt loss: delete the leader with no grace period, so connections are reset. */
    void killLeader();

    /** Graceful handover: leadership is transferred before the old leader stops serving. */
    void switchover();

    /** Restarts every member in turn, the way a node sweep moves them. */
    void rollingRestart();

    /** Waits until the storage reports a healthy leader again. */
    void awaitStable(Duration timeout);
}
