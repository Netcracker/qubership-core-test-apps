package com.netcracker.it.storage.scenario;

import com.netcracker.it.storage.controller.FaultController;

import java.time.Duration;
import java.util.function.Consumer;

/** A fault a scenario can inject. Scenarios differ only in which one, so they are driven from here. */
public enum Fault {

    /**
     * Leader deleted with no grace period, so connections are reset. The replacement comes up with
     * a new pod IP, so this is also the endpoint change the client has to follow.
     */
    ABRUPT_LEADER_LOSS("the leader is killed abruptly", Duration.ofMinutes(3),
            FaultController::killLeader),

    /** Leadership transferred before the old leader stops serving. */
    GRACEFUL_SWITCHOVER("leadership is handed over gracefully", Duration.ofMinutes(3),
            FaultController::switchover),

    /** One instance of a stateless service disappears while its peers keep serving. */
    INSTANCE_LOSS("one instance is killed while its peers serve", Duration.ofMinutes(3),
            FaultController::killLeader);

    private final String description;
    private final Duration stabilisationTimeout;
    private final Consumer<FaultController> inject;

    Fault(String description, Duration stabilisationTimeout, Consumer<FaultController> inject) {
        this.description = description;
        this.stabilisationTimeout = stabilisationTimeout;
        this.inject = inject;
    }

    /** Injects the fault and returns the instant the storage was healthy again. */
    public long injectAndAwaitRecovery(FaultController controller) {
        inject.accept(controller);
        controller.awaitStable(stabilisationTimeout);
        return System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return description;
    }
}
