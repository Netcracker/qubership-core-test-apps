package com.netcracker.cloud.storagetestservice.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** Kubernetes probes, mirroring the mesh test service rather than pulling in smallrye-health. */
@Path("/probes")
@Produces(MediaType.APPLICATION_JSON)
public class ProbesController {

    @GET
    @Path("/live")
    public Map<String, String> liveness() {
        return Map.of("status", "UP");
    }

    @GET
    @Path("/ready")
    public Map<String, String> readiness() {
        return Map.of("status", "UP");
    }
}
