package com.netcracker.it.spring.envoy;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.common.model.TraceResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.netcracker.it.spring.envoy.Paths.HELLO_PATH;
import static com.netcracker.it.spring.envoy.Paths.HELLO_VIA_PROXY;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableExtension
@Slf4j
@Tag("EnvoyFilter")
class EnvoyXRequestIdIT extends BaseTest{

    private static final String X_REQUEST_ID = "x-request-id";
    private static final int REQUEST_COUNT = 110;

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressUrls")
    // Required to fire enough requests to ensure - envoy will not change x-request-id for tracing purposes
    // refer to request_id_extension.use_request_id_for_trace_sampling,  request_id_extension.pack_trace_reason parameters
    void testExternalRequestIdPreserved(String gatewayName, URL baseUrl) throws IOException {
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            String originalId = UUID.randomUUID().toString();
            try (Response response = executeGetRequest(baseUrl, HELLO_VIA_PROXY, Map.of(X_REQUEST_ID, originalId))) {
                TraceResponse helloResponse = new Gson().fromJson(response.body().string(), TraceResponse.class);
                List<String> requestFailures = new ArrayList<>();

                if (response.code() != 200) {
                    requestFailures.add("expected status 200 but got %d".formatted(response.code()));
                }
                if (!originalId.equals(helloResponse.getXRequestId())) {
                    requestFailures.add("body xRequestId expected '%s' but was '%s'"
                            .formatted(originalId, helloResponse.getXRequestId()));
                }
                String responseRequestId = response.headers(X_REQUEST_ID).getFirst();
                if (!originalId.equals(responseRequestId)) {
                    requestFailures.add("header x-request-id expected '%s' but was '%s'"
                            .formatted(originalId, responseRequestId));
                }

                if (!requestFailures.isEmpty()) {
                    failures.add("%s [request %d/%d]: %s"
                            .formatted(gatewayName, i + 1, REQUEST_COUNT, String.join("; ", requestFailures)));
                }
            }
        }

        assertTrue(failures.isEmpty(),
                "X-Request-ID preservation failed for %d of %d requests:%n%s"
                        .formatted(failures.size(), REQUEST_COUNT, String.join("\n", failures)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressUrls")
    void testRequestIdGeneratedWhenAbsent(String gatewayName, URL baseUrl) throws IOException {
        try (Response response = executeGetRequest(baseUrl, HELLO_PATH, Collections.emptyMap())) {
            assertNotNull(response.header(X_REQUEST_ID),
                    "%s: Envoy should generate X-Request-ID when absent".formatted(gatewayName));
        }
    }
}
