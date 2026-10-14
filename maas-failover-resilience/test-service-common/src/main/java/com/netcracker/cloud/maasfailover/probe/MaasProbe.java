package com.netcracker.cloud.maasfailover.probe;

import com.netcracker.cloud.maasfailover.workload.HandleMode;

/** One storage behind a uniform contract; adding a storage is adding an implementation. */
public interface MaasProbe {

    /** Path segment this probe answers to, for example {@code maas-kafka}. */
    String type();

    /** Prepares whatever the probe needs before the first operation. */
    void init();

    /** Writes a value and reads it back, which is what one workload operation is. */
    String writeAndRead(HandleMode handleMode, String key, String value);

    /** Drops whatever the probe is holding, so the next operation starts from a clean handle. */
    void releaseHeldHandle();

    /** Diagnostics this storage can report, merged into {@code /api/v1/diag}. */
    java.util.Map<String, Object> diagnostics();
}
