package com.netcracker.cloud.storagetestservice.controller;

import com.netcracker.cloud.storagetestservice.workload.HandleMode;
import com.netcracker.cloud.storagetestservice.workload.WorkloadRunner;
import com.netcracker.cloud.storagetestservice.workload.WorkloadStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The contract the suite drives; storage-specific behaviour sits behind the {@code type} segment. */
@RestController
public class StorageController {

    private final WorkloadRunner workload;

    public StorageController(WorkloadRunner workload) {
        this.workload = workload;
    }

    @PostMapping("/api/v1/db/{type}/init")
    public ResponseEntity<Map<String, Object>> init(@PathVariable String type) {
        workload.probe(type).init();
        return ResponseEntity.ok(Map.of("storage", type, "initialised", true));
    }

    @PostMapping("/api/v1/db/{type}/write")
    public ResponseEntity<Map<String, Object>> write(@PathVariable String type,
                                                     @RequestParam String key,
                                                     @RequestParam String value,
                                                     @RequestParam(defaultValue = "PER_CALL") HandleMode handleMode) {
        String read = workload.probe(type).writeAndRead(handleMode, key, value);
        return ResponseEntity.ok(Map.of("storage", type, "key", key, "value", read));
    }

    @GetMapping("/api/v1/db/{type}/read")
    public ResponseEntity<Map<String, Object>> read(@PathVariable String type,
                                                    @RequestParam String key,
                                                    @RequestParam(defaultValue = "PER_CALL") HandleMode handleMode) {
        String value = workload.probe(type).read(handleMode, key);
        return ResponseEntity.ok(Map.of("storage", type, "key", key, "found", value != null,
                "value", value == null ? "" : value));
    }

    @PostMapping("/api/v1/workload/start")
    public ResponseEntity<Map<String, Object>> start(@RequestParam String storage,
                                                     @RequestParam(defaultValue = "PER_CALL") HandleMode handleMode,
                                                     @RequestParam(defaultValue = "10") int operationsPerSecond) {
        workload.start(storage, handleMode, operationsPerSecond);
        return ResponseEntity.ok(Map.of("storage", storage, "handleMode", handleMode,
                "operationsPerSecond", operationsPerSecond, "running", true));
    }

    @PostMapping("/api/v1/workload/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        workload.stop();
        return ResponseEntity.ok(Map.of("running", false));
    }

    @GetMapping("/api/v1/workload/stats")
    public WorkloadStats stats() {
        return workload.stats();
    }
}
