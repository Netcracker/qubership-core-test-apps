package com.netcracker.cloud.meshtestservicespring.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

import static com.netcracker.cloud.meshtestservicespring.utils.WebUtils.retryPolicy;

@Slf4j
public class ProxyService {

    @Autowired()
    @Qualifier("m2mWebClient")
    private WebClient m2mWebClient;

    public String redirect(HttpServletRequest request) {
        String url = request.getParameter("url");
        String messageUrl = "http://" + url;
        log.info("Sending request to '{}' to get message", messageUrl);
        String response = getRequest(messageUrl);
        log.info("Response:{}", response);
        return response;
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
