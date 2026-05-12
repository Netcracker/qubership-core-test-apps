package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.meshtestservicespring.model.ProxyResponse;
import com.netcracker.cloud.meshtestservicespring.service.ProxyHeadersService;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/proxy-headers")
@Route(RouteType.PUBLIC)
@GatewayRequestMapping(path = ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/proxy-headers")
@Slf4j
public class ProxyHeadersController {

    @Autowired
    private ProxyHeadersService proxyHeadersService;

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<ProxyResponse> proxyHeaders(HttpServletRequest request) {
        String url = request.getParameter("url");
        return ResponseEntity.ok(proxyHeadersService.getResponse(url));
    }
}