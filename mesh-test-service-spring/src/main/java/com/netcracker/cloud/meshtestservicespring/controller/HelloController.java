package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.routesregistration.common.annotation.Route;
import com.netcracker.cloud.routesregistration.common.gateway.route.RouteType;
import com.netcracker.cloud.routesregistration.common.spring.gateway.route.annotation.GatewayRequestMapping;
import com.netcracker.cloud.meshtestservicespring.service.HelloService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/hello")
@Route(RouteType.PUBLIC)
@GatewayRequestMapping(path = ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/hello")
@Slf4j
public class HelloController {

    @Autowired
    private HelloService helloService;

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> hello(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, request.getHeader(name)));
        return ResponseEntity.ok(helloService.hello(request, headers));
    }

    @RequestMapping(method = RequestMethod.GET, path = "/quarkus")
    public ResponseEntity<String> helloQuarkus(@Nullable @RequestParam String namespace) {
        String res = helloService.helloQuarkus(namespace);
        return ResponseEntity.ok(res);
    }

}

