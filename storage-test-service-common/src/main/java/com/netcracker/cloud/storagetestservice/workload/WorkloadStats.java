package com.netcracker.cloud.storagetestservice.workload;

import java.util.List;

/** The timeline the suite asserts on, plus counters that make a failure readable. */
public record WorkloadStats(
        boolean running,
        String storage,
        HandleMode handleMode,
        long startedAtMillis,
        long total,
        long succeeded,
        long failed,
        Long firstFailureAtMillis,
        Long lastFailureAtMillis,
        Long firstSuccessAfterLastFailureMillis,
        long maxDurationMillis,
        List<OperationOutcome> outcomes) {
}
