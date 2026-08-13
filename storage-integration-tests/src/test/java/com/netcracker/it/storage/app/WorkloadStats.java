package com.netcracker.it.storage.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The timeline the application recorded, as returned by {@code /api/v1/workload/stats}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkloadStats(
        boolean running,
        String storage,
        String handleMode,
        long startedAtMillis,
        long total,
        long succeeded,
        long failed,
        Long firstFailureAtMillis,
        Long lastFailureAtMillis,
        Long firstSuccessAfterLastFailureMillis,
        long maxDurationMillis,
        List<OperationOutcome> outcomes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OperationOutcome(
            long sequence,
            long startedAtMillis,
            long durationMillis,
            boolean success,
            String errorClass,
            String errorMessage) {
    }

    /** Operations that started at or after the given instant. */
    public List<OperationOutcome> since(long millis) {
        return outcomes.stream().filter(o -> o.startedAtMillis() >= millis).toList();
    }

    /** How long the application was failing continuously, or zero when it never failed. */
    public long failureWindowMillis() {
        if (firstFailureAtMillis == null || lastFailureAtMillis == null) {
            return 0;
        }
        return lastFailureAtMillis - firstFailureAtMillis;
    }
}
