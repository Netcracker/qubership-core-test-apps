package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.spring.gateway.route.annotation.GatewayRequestMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Nullable;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/sleep")
@Route(RouteType.PUBLIC)
@GatewayRequestMapping(path = ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/sleep")
@Slf4j
public class SleepController {


    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> sleep(@RequestParam @Nullable int seconds) throws InterruptedException {
        if (seconds == 0) seconds = 60;
        Thread.sleep(seconds*1000);
        return ResponseEntity.ok("wake up!");
    }

}

