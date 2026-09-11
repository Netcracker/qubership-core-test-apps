package com.netcracker.cloud.storagetestservice.workload;

/** One operation the workload performed, as the application saw it. */
public record OperationOutcome(
        long sequence,
        long startedAtMillis,
        long durationMillis,
        boolean success,
        String errorClass,
        String errorMessage) {

    public static OperationOutcome ok(long sequence, long startedAtMillis, long durationMillis) {
        return new OperationOutcome(sequence, startedAtMillis, durationMillis, true, null, null);
    }

    public static OperationOutcome failed(long sequence, long startedAtMillis, long durationMillis, Throwable error) {
        // the root cause is what classifies the failure; the wrapper rarely says anything useful
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return new OperationOutcome(sequence, startedAtMillis, durationMillis, false,
                root.getClass().getName(), root.getMessage());
    }
}
