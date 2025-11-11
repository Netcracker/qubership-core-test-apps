package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.meshtestservicespring.service.EgressService;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.spring.gateway.route.annotation.GatewayRequestMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/egress")
@Route(RouteType.PUBLIC)
@GatewayRequestMapping(path = ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/egress")
@Slf4j
public class EgressController {

    @Autowired
    private EgressService egressService;

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok(egressService.callEgress());
    }
}

