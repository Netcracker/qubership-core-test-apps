package com.netcracker.cloud.storagetestservice.controller;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Puts the cause in the response body. The default error page carries only a status, which turns
 * every failure in the suite into a second run with the pod logs.
 */
@RestControllerAdvice
public class FailureDetail {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onFailure(Exception e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", e.getClass().getName(),
                "message", String.valueOf(e.getMessage()),
                "cause", root.getClass().getName() + ": " + root.getMessage()));
    }
}
