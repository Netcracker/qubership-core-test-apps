package com.netcracker.cloud.meshtestservicespring.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

import static com.netcracker.cloud.meshtestservicespring.utils.WebUtils.retryPolicy;

@Slf4j
@RequiredArgsConstructor
public class EgressService {
    private final String egressUrl;

    @Autowired()
    @Qualifier("m2mWebClient")
    private WebClient m2mWebClient;

    public String callEgress() {
        log.info("Call egress. Sending request to '{}' to get message", egressUrl);
        String response = getRequest(egressUrl);
        String responseMessage = String.format("Egress answered:%s", response);
        log.info(responseMessage);
        return responseMessage;
    }

    private String getRequest(String url) {
        WebClient.ResponseSpec responseSpec = m2mWebClient.get()
                .uri(url)
                .retrieve();

        return responseSpec
                .bodyToMono(String.class)
                .retryWhen(retryPolicy)
                .block();
    }
}
