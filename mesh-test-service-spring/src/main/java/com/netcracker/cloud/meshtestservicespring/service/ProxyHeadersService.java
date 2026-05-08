package com.netcracker.cloud.meshtestservicespring.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ProxyHeadersService {

    @Autowired()
    @Qualifier("m2mWebClient")
    private WebClient m2mWebClient;

    public Map<String, List<String>> getHeaders(String url) {
        String fullUrl = "http://" + url;
        log.info("Fetching headers from '{}'", fullUrl);
        return m2mWebClient
                .get()
                .uri(fullUrl)
                .exchangeToMono(response -> {
                    log.info("Upstream status: {}", response.statusCode());
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .doOnNext(body -> log.error(
                                        "Upstream error status={} body={}",
                                        response.statusCode(), body))
                                .<Map<String, List<String>>>flatMap(body -> Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.valueOf(response.statusCode().value()),
                                                "Upstream error: " + body)));
                    }
                    return response.toBodilessEntity()
                            .map(entity -> {
                                Map<String, List<String>> result = new HashMap<>();
                                entity.getHeaders().forEach(result::put);
                                return result;
                            });
                })
                .block();
    }
}
