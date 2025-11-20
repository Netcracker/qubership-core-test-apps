package com.netcracker.quarkus.controller;

import com.google.gson.Gson;
import com.netcracker.cloud.routesregistration.common.annotation.Gateway;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.quarkus.ApiVersions;
import com.netcracker.quarkus.client.HelloGoService;
import com.netcracker.quarkus.model.TraceResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.UUID;

@Produces(MediaType.TEXT_PLAIN)
@Route(RouteType.PUBLIC)
@Gateway(ApiVersions.API +ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/hello")
@Path(ApiVersions.API + ApiVersions.V1 + "/hello")
@Slf4j
public class Controller {

    @Inject
    @RestClient // don't forget to specify endpoint in application.properties
    HelloGoService helloGoService;

    @ConfigProperty(name = "deployment.version")
    private String deploymentVersion;

    @ConfigProperty(name = "cloud.microservice.name")
    private String familyName;

    @ConfigProperty(name = "quarkus.application.cloud_service_name")
    private String serviceName;

    @ConfigProperty(name = "HOSTNAME")
    private String hostName;

    @ConfigProperty(name = "cloud.microservice.namespace")
    private String namespace;


    private final String podId = UUID.randomUUID().toString();


    @GET
    public String hello( @Context ContainerRequestContext request, @Context UriInfo uriInfo) {
        log.info("hello");
        TraceResponse response = new TraceResponse();
        response.setServiceName(serviceName);
        response.setFamilyName(familyName);
        response.setVersion(deploymentVersion);
        response.setNamespace(namespace);
        response.setPodId(podId);
        response.setXversion(request.getHeaderString("X-Version"));
        response.setXVersionName(request.getHeaderString("x-version-name"));
        response.setRemoteAddr(uriInfo.getPath());
        response.setServerHost(hostName);
        log.info("Responding with service name:{} version:{}", response.getServiceName(), response.getVersion());
        return new Gson().toJson(response);
    }

    @GET
    @Path("/go")
    @Produces(MediaType.TEXT_PLAIN)
    public String helloGo() {
        log.info("hello from go");
        TraceResponse response = helloGoService.hello();
        log.info("Go service answered:{}", response.toString());
        return new Gson().toJson(response);
    }
}
