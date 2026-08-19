package com.netcracker.cloud.storagetestservice.controller;

import com.netcracker.cloud.storagetestservice.diag.Diagnostics;
import com.netcracker.cloud.storagetestservice.workload.WorkloadRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** What the leak scenario compares: after N fault cycles these must return to baseline. */
@RestController
public class DiagController {

    private final WorkloadRunner workload;

    public DiagController(WorkloadRunner workload) {
        this.workload = workload;
    }

    @GetMapping("/api/v1/diag")
    public Map<String, Object> diag() {
        return Diagnostics.of(workload.probes());
    }
}
