package com.netcracker.cloud.meshtestservicespring.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Enumeration;

import static com.netcracker.cloud.meshtestservicespring.utils.WebUtils.X_REQUEST_ID;

@Slf4j
public class ProxyService {

    @Autowired()
    @Qualifier("m2mWebClient")
    private WebClient m2mWebClient;

    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        String url = request.getParameter("url");
        String messageUrl = "http://" + url;
        log.info("Proxying GET request to '{}'", messageUrl);
        try {
            ResponseEntity<byte[]> response = m2mWebClient.method(HttpMethod.GET)
                    .uri(messageUrl)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), clientResponse -> Mono.empty())
                    .toEntity(byte[].class)
                    .block();

            log.info("Proxied response status: {}", response != null ? response.getStatusCode() : "empty");
            //exclude x-request-id, as ingress gateway will add it second time and header will have value like [id, id]
            return response != null ? removeXRequestId(response) : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.warn("Proxy request to '{}' did not receive an upstream response", messageUrl, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private ResponseEntity<byte[]> removeXRequestId(ResponseEntity<byte[]> response) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.remove(X_REQUEST_ID);
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }
}
