package com.netcracker.it.storage.scenario;

import com.netcracker.it.storage.app.WorkloadStats;
import com.netcracker.it.storage.app.WorkloadStats.OperationOutcome;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * How long after its first success the client is still allowed to fail. Recovery is not
     * instantaneous across a connection pool, but it is not open-ended either.
     */
    private static final long SETTLING_MILLIS = 10_000;

    /** The whole contract in one call, so a scenario reads as "inject the fault, assert this". */
    public static void assertContract(WorkloadStats stats, long faultClearedAtMillis,
                                      Thresholds thresholds, long minimumOperations) {
        assertWorkloadRan(stats, minimumOperations);
        assertRecovered(stats, faultClearedAtMillis, thresholds);
        assertErrorsStopped(stats, faultClearedAtMillis);
        assertNothingHung(stats, thresholds);
        assertStatusesAreClassified(stats);
    }

    /**
     * Statuses the client is built to survive: a demoted leader answers 405, an agent that cannot
     * reach maas-service answers 5xx, and a throttled call answers 429.
     */
    private static final Set<String> EXPECTED_STATUSES = Set.of("405", "429", "500", "502", "503", "504");

    /** Matches the status in both clients' error text: "status code 405", "status 405", "Status: 500". */
    private static final Pattern STATUS_IN_MESSAGE = Pattern.compile("(?i)status(?: code)?[\"\\s:]+(\\d{3})");

    /**
     * A maas-service defect, not a client one. When the master is unavailable, maas-service replays
     * the query against its in-memory SQLite replica, where PostgreSQL array columns cannot exist.
     * The dialect error replaces the availability error that caused the fallback, and the
     * controller cannot tell it from bad input, so a get-or-create that would have been a retryable
     * 5xx arrives as a permanent 400. Excluded here so the check still catches every other status.
     */
    private static final Pattern REPLICA_DIALECT_DEFECT = Pattern.compile("no such function: ANY");

    /**
     * Every status the run produced is one the client classifies as worth retrying. A failure
     * carrying anything else means a switchover reaches the client in a shape the classification
     * does not cover, which no unit test can discover. Failures without a status - a reset
     * connection, a watch callback that never arrived - are not statuses and do not apply.
     */
    public static void assertStatusesAreClassified(WorkloadStats stats) {
        Set<String> observed = new LinkedHashSet<>();
        for (OperationOutcome failure : stats.outcomes()) {
            if (failure.success() || failure.errorMessage() == null) {
                continue;
            }
            if (REPLICA_DIALECT_DEFECT.matcher(failure.errorMessage()).find()) {
                continue;
            }
            Matcher matcher = STATUS_IN_MESSAGE.matcher(failure.errorMessage());
            while (matcher.find()) {
                observed.add(matcher.group(1));
            }
        }
        assertThat(observed)
                .as("HTTP statuses seen during the run; anything outside this set is a failure mode "
                        + "the client does not classify")
                .isSubsetOf(EXPECTED_STATUSES);
    }

    /** Without this every other assertion passes vacuously when nothing ever ran. */
    public static void assertWorkloadRan(WorkloadStats stats, long minimumOperations) {
        assertThat(stats.total())
                .as("operations recorded by the application")
                .isGreaterThanOrEqualTo(minimumOperations);
    }

    /** A successful operation occurred within the recovery allowance after the fault cleared. */
    public static void assertRecovered(WorkloadStats stats, long faultClearedAtMillis, Thresholds thresholds) {
        List<OperationOutcome> after = stats.since(faultClearedAtMillis);
        OperationOutcome firstSuccess = firstSuccessAfter(stats, faultClearedAtMillis).orElse(null);

        assertThat(firstSuccess)
                .as("first success after the fault cleared; errors seen: %s", errorSummary(after))
                .isNotNull();
        assertThat(firstSuccess.startedAtMillis() - faultClearedAtMillis)
                .as("recovery time")
                .isLessThanOrEqualTo(thresholds.recovery().toMillis());
    }

    /**
     * Errors stopped once the client had recovered. Measured from the first success rather than
     * from the recovery allowance, so a client that recovers quickly is still held to the same
     * standard, and the scenario does not have to wait out the allowance to check it.
     */
    public static void assertErrorsStopped(WorkloadStats stats, long faultClearedAtMillis) {
        long recoveredAt = firstSuccessAfter(stats, faultClearedAtMillis)
                .map(OperationOutcome::startedAtMillis)
                .orElseThrow(() -> new AssertionError("the client never recovered, so there is "
                        + "nothing to measure quiet traffic against"));

        List<OperationOutcome> settled = stats.since(recoveredAt + SETTLING_MILLIS);
        assertThat(settled)
                .as("operations recorded after the client settled; without them this check is vacuous")
                .isNotEmpty();
        assertThat(settled.stream().filter(outcome -> !outcome.success()).toList())
                .as("failures after the client settled: %s", errorSummary(settled))
                .isEmpty();
    }

    /** The first operation that succeeded once the storage was healthy again. */
    static Optional<OperationOutcome> firstSuccessAfter(WorkloadStats stats, long millis) {
        return stats.since(millis).stream().filter(OperationOutcome::success).findFirst();
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

    /**
     * What the fault actually did, for the run log. A scenario where nothing ever failed is not
     * measuring recovery, and only this line makes that visible.
     */
    public static String summarise(WorkloadStats stats, long faultClearedAtMillis) {
        List<OperationOutcome> failures = stats.outcomes().stream()
                .filter(outcome -> !outcome.success())
                .toList();
        String recovery = firstSuccessAfter(stats, faultClearedAtMillis)
                .map(first -> (first.startedAtMillis() - faultClearedAtMillis) + "ms")
                .orElse("never");

        return String.format(
                "operations=%d succeeded=%d failed=%d, failure window=%dms, recovery after fault cleared=%s,"
                        + " slowest operation=%dms, errors: %s",
                stats.total(), stats.succeeded(), stats.failed(), stats.failureWindowMillis(),
                recovery, stats.maxDurationMillis(), errorSummary(failures));
    }

    /**
     * Failures grouped by error class, then by distinct message, so a failed assertion says what
     * went wrong and which statuses maas-service produced. The status a demoted leader answers
     * with is the assumption the retry classification rests on, and the messages are where the
     * run reports it.
     */
    public static String errorSummary(List<OperationOutcome> outcomes) {
        List<OperationOutcome> failures = outcomes.stream().filter(o -> !o.success()).toList();
        if (failures.isEmpty()) {
            return "none";
        }
        Map<String, Long> byClass = failures.stream().collect(Collectors.groupingBy(
                outcome -> outcome.errorClass() == null ? "unknown" : outcome.errorClass(),
                Collectors.counting()));
        Map<String, Long> byMessage = failures.stream().collect(Collectors.groupingBy(
                outcome -> outcome.errorMessage() == null ? "no message" : outcome.errorMessage(),
                Collectors.counting()));
        return byClass + ", messages: " + byMessage;
    }
}
