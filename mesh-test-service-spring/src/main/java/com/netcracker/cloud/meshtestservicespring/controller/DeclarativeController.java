package com.netcracker.cloud.meshtestservicespring.controller;

import com.netcracker.cloud.meshtestservicespring.configuration.ApiVersions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiVersions.API + ApiVersions.V1 + "/declarative_hello")
@Slf4j
public class DeclarativeController {

    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Declarative hello from spring mesh test service");
    }
}
