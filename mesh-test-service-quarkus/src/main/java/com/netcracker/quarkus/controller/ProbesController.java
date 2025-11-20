package com.netcracker.quarkus.controller;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;


@Path("/probes")
@Slf4j
public class ProbesController {

    @Operation(hidden = true)
    @GET
    @Path("/live")
    public Response livenessProbe() {
        return Response.ok("{\"status\" : \"UP\"}").build();
    }
}
