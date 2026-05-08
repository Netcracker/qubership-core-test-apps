package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.spring.gateway.route.annotation.GatewayRequestMapping;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/request-headers")
@Route(RouteType.PUBLIC)
@GatewayRequestMapping(path = ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/request-headers")
@Slf4j
public class RequestHeadersController {

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<Map<String, String>> headers(HttpServletRequest request) {
        log.info("Handle /request-headers request");
        Map<String, String> headers = Collections.list(request.getHeaderNames())
                .stream()
                .collect(Collectors.toMap(
                        name -> name,
                        request::getHeader
                ));
        return ResponseEntity.ok(headers);
    }
}