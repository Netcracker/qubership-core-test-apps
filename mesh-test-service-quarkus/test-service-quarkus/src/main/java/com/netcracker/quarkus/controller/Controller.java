package com.netcracker.quarkus.controller;

import com.google.gson.Gson;
import com.netcracker.cloud.routesregistration.common.annotation.Gateway;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.quarkus.ApiVersions;
import com.netcracker.quarkus.client.HelloGoService;
import com.netcracker.quarkus.model.TraceResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
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


    private String podId = UUID.randomUUID().toString();


    @GET
    public String hello(@Context HttpServletRequest request) {
        log.info("hello");
        TraceResponse response = new TraceResponse();
        response.setServiceName(serviceName);
        response.setFamilyName(familyName);
        response.setVersion(deploymentVersion);
        response.setNamespace(namespace);
        response.setPodId(podId);
        response.setXversion(request.getHeader("X-Version"));
        response.setXVersionName(request.getHeader("x-version-name"));
        response.setRemoteAddr(request.getRemoteHost());
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
