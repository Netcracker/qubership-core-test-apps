package com.netcracker.it.storage.scenario;

import com.netcracker.it.storage.app.WorkloadStats;
import com.netcracker.it.storage.app.WorkloadStats.OperationOutcome;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract every scenario asserts, evaluated against the timeline the application recorded.
 * Not zero-error: a leader change produces errors, and what is asserted is that they are bounded,
 * recoverable and correctly classified.
 */
public final class StorageAssertions {

    private StorageAssertions() {
    }

    /** The whole contract in one call, so a scenario reads as "inject the fault, assert this". */
    public static void assertContract(WorkloadStats stats, long faultClearedAtMillis,
                                      Thresholds thresholds, long minimumOperations) {
        assertWorkloadRan(stats, minimumOperations);
        assertRecovered(stats, faultClearedAtMillis, thresholds);
        assertErrorsStopped(stats, faultClearedAtMillis, thresholds);
        assertNothingHung(stats, thresholds);
    }

    /** Without this every other assertion passes vacuously when nothing ever ran. */
    public static void assertWorkloadRan(WorkloadStats stats, long minimumOperations) {
        assertThat(stats.total())
                .as("operations recorded by the application")
                .isGreaterThanOrEqualTo(minimumOperations);
    }

    /** A successful operation occurred within the recovery budget after the fault cleared. */
    public static void assertRecovered(WorkloadStats stats, long faultClearedAtMillis, Thresholds thresholds) {
        List<OperationOutcome> after = stats.since(faultClearedAtMillis);
        OperationOutcome firstSuccess = after.stream()
                .filter(OperationOutcome::success)
                .findFirst()
                .orElse(null);

        assertThat(firstSuccess)
                .as("first success after the fault cleared; errors seen: %s", errorSummary(after))
                .isNotNull();
        assertThat(firstSuccess.startedAtMillis() - faultClearedAtMillis)
                .as("recovery time")
                .isLessThanOrEqualTo(thresholds.recovery().toMillis());
    }

    /**
     * Errors stopped once the storage was healthy again. A client still failing long afterwards is
     * the defect this looks for: a cached connection that is never rebuilt.
     */
    public static void assertErrorsStopped(WorkloadStats stats, long faultClearedAtMillis, Thresholds thresholds) {
        List<OperationOutcome> late = stats.since(faultClearedAtMillis + thresholds.recovery().toMillis())
                .stream().filter(outcome -> !outcome.success()).toList();

        assertThat(late)
                .as("failures after recovery settled: %s", errorSummary(late))
                .isEmpty();
    }

    /** Every operation returned, success or error. A hung call is a hard failure. */
    public static void assertNothingHung(WorkloadStats stats, Thresholds thresholds) {
        assertThat(stats.maxDurationMillis())
                .as("slowest operation")
                .isLessThanOrEqualTo(thresholds.maxOperation().toMillis());
    }

    /** Threads and descriptors returned to baseline after repeated fault cycles. */
    public static void assertNoLeak(Map<String, Object> baseline, Map<String, Object> after, Thresholds thresholds) {
        assertWithinTolerance(baseline, after, "threadCount", thresholds.leakTolerance());
        assertWithinTolerance(baseline, after, "openFileDescriptors", thresholds.leakTolerance());
    }

    private static void assertWithinTolerance(Map<String, Object> baseline, Map<String, Object> after,
                                              String key, double tolerance) {
        Number before = (Number) baseline.get(key);
        Number now = (Number) after.get(key);
        if (before == null || now == null || before.longValue() < 0 || now.longValue() < 0) {
            return; // counter not exposed on this JVM
        }
        assertThat(now.longValue())
                .as("%s after the fault cycles (baseline %s)", key, before)
                .isLessThanOrEqualTo(Math.round(before.longValue() * (1 + tolerance)) + 5);
    }

    /** Failures grouped by error class, so a failed assertion says what went wrong. */
    public static String errorSummary(List<OperationOutcome> outcomes) {
        List<OperationOutcome> failures = outcomes.stream().filter(o -> !o.success()).toList();
        if (failures.isEmpty()) {
            return "none";
        }
        Map<String, Long> byClass = failures.stream().collect(Collectors.groupingBy(
                outcome -> outcome.errorClass() == null ? "unknown" : outcome.errorClass(),
                Collectors.counting()));
        return byClass + ", first message: " + failures.get(0).errorMessage();
    }
}
