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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;

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
        validateUrl(url);
        return ResponseEntity.ok(proxyHeadersService.getResponse(url));
    }

    private static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Query parameter 'url' is required");
        }
        URI uri;
        try {
            uri = new URI("http://" + url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Malformed 'url' value: " + e.getMessage());
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Query parameter 'url' must contain a host");
        }
        String scheme = uri.getScheme();
        if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only http/https schemes are permitted, got: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Embedded credentials are not permitted in 'url'");
        }
    }
}
