package com.netcracker.quarkus.client;

//import com.netcracker.cloud.core.quarkus.security.m2m.rest.M2MFilter;
import com.netcracker.quarkus.model.TraceResponse;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/v1/mesh-test-service-go")
//@RegisterProvider(M2MFilter.class)
@RegisterRestClient(baseUri = "http://mesh-test-service-go:8080")
public interface HelloGoService {

    @GET
    @Path("/hello")
    @Produces(MediaType.APPLICATION_JSON)
    TraceResponse hello();
}