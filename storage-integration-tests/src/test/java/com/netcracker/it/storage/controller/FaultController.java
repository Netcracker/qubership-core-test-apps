package com.netcracker.it.storage.controller;

import java.time.Duration;

/**
 * The faults a scenario can inject, in one vocabulary for every storage. Implementations are a
 * Kubernetes call or an {@code exec} of the storage's own admin CLI.
 */
public interface FaultController {

    /** Storage this controller drives, matching the {@code type} segment of the app contract. */
    String storage();

    /** Name of the pod currently holding leadership. */
    String leaderPod();

    /** Abrupt loss: delete the leader with no grace period, so connections are reset. */
    void killLeader();

    /** Graceful handover: leadership is transferred before the old leader stops serving. */
    void switchover();

    /** Packets dropped without a reset - the mode that hangs clients, unlike pod deletion. */
    void blackhole(Duration duration);

    /** Restarts every member in turn, the way a node sweep moves them. */
    void rollingRestart();

    /** Marks a node unschedulable and evicts its pods. Requires a multi-node cluster. */
    void cordonAndDrain(String node);

    /** Undoes {@link #cordonAndDrain(String)}. */
    void uncordon(String node);

    /** Waits until the storage reports a healthy leader again. */
    void awaitStable(Duration timeout);
}
