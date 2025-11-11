package com.netcracker.cloud.meshtestservicespring.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
public class WebUtils {
    public static final Retry retryPolicy = Retry.fixedDelay(12, Duration.ofSeconds(5))
            .filter(throwable -> {
                log.error("Retry failed request. {}", throwable.getMessage());
                return throwable instanceof WebClientResponseException &&
                        ((WebClientResponseException) throwable).getStatusCode().value() >= 503;
            });
}
