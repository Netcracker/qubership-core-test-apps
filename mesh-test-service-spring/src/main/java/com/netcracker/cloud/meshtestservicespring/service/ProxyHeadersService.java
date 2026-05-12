package com.netcracker.cloud.meshtestservicespring.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import com.netcracker.cloud.meshtestservicespring.model.ProxyResponse;

import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ProxyHeadersService {

    @Autowired()
    @Qualifier("m2mWebClient")
    private WebClient m2mWebClient;

    public ProxyResponse getResponse(String url) {
        String fullUrl = "http://" + url;
        log.info("Fetching headers from '{}'", fullUrl);
        return WebClient.create()
                .get()
                .uri(fullUrl)
                .exchangeToMono(response -> {
                    log.info("Upstream status: {}", response.statusCode());
                    int status = response.statusCode().value();
                    return response.toBodilessEntity()
                            .map(entity -> {
                                Map<String, List<String>> headers = new HashMap<>();
                                entity.getHeaders().forEach(headers::put);
                                ProxyResponse proxyResponse = new ProxyResponse();
                                proxyResponse.setStatus(status);
                                proxyResponse.setHeaders(headers);
                                return proxyResponse;
                            });
                })
                .block();
    }
}
