package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.meshtestservicespring.service.ProxyService;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.spring.gateway.route.annotation.GatewayRequestMapping;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/spring/proxy")
@Route(RouteType.PUBLIC)
@GatewayRequestMapping(path = ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/spring/proxy")
@Slf4j
public class ProxyController {

    @Autowired
    private ProxyService proxyService;

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> proxy(HttpServletRequest request) {
        return ResponseEntity.ok(proxyService.redirect(request));
    }
}

