package com.netcracker.cloud.meshtestservicespring.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
public class WebUtils {

    public static final String X_REQUEST_ID = "x-request-id";

    private WebUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static final Retry retryPolicy = Retry.fixedDelay(12, Duration.ofSeconds(5))
            .filter(throwable -> {
                log.error("Retry failed request. {}", throwable.getMessage());
                return throwable instanceof WebClientResponseException webClientResponseException &&
                        isRecoverableCode(webClientResponseException.getStatusCode().value());
            });

    /**
     * Do not recover 504 - GW timeout. In some tests it is expected result, in other - requests processing should fit default timeout
     * @param code
     * @return
     */
    private static boolean isRecoverableCode(int code) {
        return code == 503 || code > 504  ;
    }
}
