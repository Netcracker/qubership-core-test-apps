package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import com.netcracker.cloud.meshtestservicespring.model.LuaFilterTraceResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + ApiVersions.SERVICE_NAME + "/lua-filter")
@Slf4j
public class LuaFilterController {

    @GetMapping("/**")
    public ResponseEntity<LuaFilterTraceResponse> trace(HttpServletRequest request) {
        log.info("Handle lua-filter probe: {}", request.getRequestURI());
        return ResponseEntity.ok(LuaFilterTraceResponse.fromRequest(request));
    }
}
