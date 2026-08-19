package com.netcracker.cloud.storagetestservice.controller;

import com.netcracker.cloud.storagetestservice.workload.HandleMode;
import com.netcracker.cloud.storagetestservice.workload.WorkloadRunner;
import com.netcracker.cloud.storagetestservice.workload.WorkloadStats;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

/** The contract the suite drives; storage-specific behaviour sits behind the {@code type} segment. */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class StorageController {

    private final WorkloadRunner workload;

    @Inject
    public StorageController(WorkloadRunner workload) {
        this.workload = workload;
    }

    @POST
    @Path("/db/{type}/init")
    public Map<String, Object> init(@PathParam("type") String type) {
        workload.probe(type).init();
        return Map.of("storage", type, "initialised", true);
    }

    @POST
    @Path("/db/{type}/write")
    public Map<String, Object> write(@PathParam("type") String type,
                                     @QueryParam("key") String key,
                                     @QueryParam("value") String value,
                                     @QueryParam("handleMode") @DefaultValue("PER_CALL") HandleMode handleMode) {
        String read = workload.probe(type).writeAndRead(handleMode, key, value);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("storage", type);
        response.put("key", key);
        response.put("value", read);
        return response;
    }

    @GET
    @Path("/db/{type}/read")
    public Map<String, Object> read(@PathParam("type") String type,
                                    @QueryParam("key") String key,
                                    @QueryParam("handleMode") @DefaultValue("PER_CALL") HandleMode handleMode) {
        String value = workload.probe(type).read(handleMode, key);
        return Map.of("storage", type, "key", key, "found", value != null,
                "value", value == null ? "" : value);
    }

    @POST
    @Path("/workload/start")
    public Map<String, Object> start(@QueryParam("storage") String storage,
                                     @QueryParam("handleMode") @DefaultValue("PER_CALL") HandleMode handleMode,
                                     @QueryParam("operationsPerSecond") @DefaultValue("10") int operationsPerSecond) {
        workload.start(storage, handleMode, operationsPerSecond);
        return Map.of("storage", storage, "handleMode", handleMode,
                "operationsPerSecond", operationsPerSecond, "running", true);
    }

    @POST
    @Path("/workload/stop")
    public Map<String, Object> stop() {
        workload.stop();
        return Map.of("running", false);
    }

    @GET
    @Path("/workload/stats")
    public WorkloadStats stats() {
        return workload.stats();
    }
}
