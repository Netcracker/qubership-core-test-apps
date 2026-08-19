package com.netcracker.cloud.storagetestservice.controller;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Puts the cause in the response body. The default Quarkus error carries only an id to look up in
 * the pod log, which turns every failure in the suite into a second run.
 */
@Provider
public class FailureDetail implements ExceptionMapper<Exception> {

    @Override
    public Response toResponse(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return Response.serverError().entity(Map.of(
                "error", e.getClass().getName(),
                "message", String.valueOf(e.getMessage()),
                "cause", root.getClass().getName() + ": " + root.getMessage())).build();
    }
}
