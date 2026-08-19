package com.netcracker.cloud.storagetestservice.diag;

import com.netcracker.cloud.storagetestservice.storage.StorageProbe;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** What the leak scenario compares: after N fault cycles these must return to baseline. */
public final class Diagnostics {

    private Diagnostics() {
    }

    public static Map<String, Object> of(List<StorageProbe> probes) {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        diag.put("peakThreadCount", ManagementFactory.getThreadMXBean().getPeakThreadCount());
        diag.put("openFileDescriptors", openFileDescriptors());
        diag.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        diag.put("storages", probes.stream()
                .collect(Collectors.toMap(StorageProbe::type, StorageProbe::diagnostics)));
        return diag;
    }

    /** Open descriptors, when the JVM exposes them: every leaked connection is one. */
    private static long openFileDescriptors() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }
}
