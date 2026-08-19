package com.netcracker.cloud.storagetestservice.controller;

import com.netcracker.cloud.storagetestservice.diag.Diagnostics;
import com.netcracker.cloud.storagetestservice.workload.WorkloadRunner;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** What the leak scenario compares: after N fault cycles these must return to baseline. */
@Path("/api/v1/diag")
@Produces(MediaType.APPLICATION_JSON)
public class DiagController {

    private final WorkloadRunner workload;

    @Inject
    public DiagController(WorkloadRunner workload) {
        this.workload = workload;
    }

    @GET
    public Map<String, Object> diag() {
        return Diagnostics.of(workload.probes());
    }
}
