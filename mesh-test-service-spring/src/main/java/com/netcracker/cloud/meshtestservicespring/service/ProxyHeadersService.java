package com.netcracker.cloud.meshtestservicespring.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
public class ProxyHeadersService {

    public Map<String, List<String>> getHeaders(String url) {
        String fullUrl = "http://" + url;
        log.info("Fetching headers from '{}'", fullUrl);
        return WebClient.create()
                .get()
                .uri(fullUrl)
                .exchangeToMono(response -> response.toBodilessEntity()
                        .map(entity -> (Map<String, List<String>>) entity.getHeaders()))
                .block();
    }
}

