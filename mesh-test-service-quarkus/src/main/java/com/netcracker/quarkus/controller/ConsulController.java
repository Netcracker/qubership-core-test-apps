package com.netcracker.quarkus.controller;

import com.netcracker.cloud.routesregistration.common.annotation.Gateway;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.quarkus.ApiVersions;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.ConfigProvider;

@Produces(MediaType.TEXT_PLAIN)
@Route(RouteType.PUBLIC)
@Gateway(ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/config")
@Path(ApiVersions.API + ApiVersions.V1 + "/config")
@Slf4j
public class ConsulController {

    @GET
    public String config() {
        return ConfigProvider.getConfig().getConfigValue("consul.test.property").getValue();
    }
}
