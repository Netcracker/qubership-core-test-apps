package com.netcracker.quarkus.controller;

import com.netcracker.cloud.routesregistration.common.annotation.Gateway;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.quarkus.ApiVersions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;

@Route(RouteType.PUBLIC)
@Gateway(ApiVersions.API +ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/sleep")
@Path(ApiVersions.API + ApiVersions.V1 + "/sleep")
public class SleepController {

    @GET
    public String sleep(@Context HttpServletRequest request) throws InterruptedException {
        int seconds = Integer.parseInt(request.getParameter("seconds"));
        if (seconds == 0) seconds = 60;
        Thread.sleep(seconds*1000);
        return"wake up!";
    }
}
