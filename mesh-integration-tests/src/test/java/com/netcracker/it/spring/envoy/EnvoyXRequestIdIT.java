package com.netcracker.it.spring.envoy;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.common.model.TraceResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.netcracker.it.spring.envoy.Paths.HELLO_PATH;
import static com.netcracker.it.spring.envoy.Paths.HELLO_VIA_PROXY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableExtension
@Slf4j
@Tag("EnvoyFilter")
class EnvoyXRequestIdIT extends BaseTest{

    private static final String X_REQUEST_ID = "x-request-id";
    private static final int REQUEST_COUNT = 200;

    static class RepeatedArgs implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.generate(EnvoyXRequestIdIT::ingressUrls).limit(REQUEST_COUNT).flatMap(s -> s);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ArgumentsSource(RepeatedArgs.class)
    // Required to fire enough requests to ensure - envoy will not change x-request-id for tracing purposes
    // refer to request_id_extension.use_request_id_for_trace_sampling,  request_id_extension.pack_trace_reason parameters
    void testExternalRequestIdPreserved(String gatewayName, URL baseUrl) throws IOException {
        String originalId = UUID.randomUUID().toString();
        try (Response response = executeGetRequest(baseUrl, HELLO_VIA_PROXY, Map.of(X_REQUEST_ID, originalId))) {
            TraceResponse helloResponse = new Gson().fromJson(response.body().string(), TraceResponse.class);
            assertEquals(200, response.code());
            String assertMsg = "%s: Envoy must not modify X-Request-ID supplied by the client".formatted(gatewayName);
            assertEquals(originalId, helloResponse.getXRequestId(), assertMsg);
            assertEquals(originalId, response.headers(X_REQUEST_ID).getFirst(), assertMsg);
        }
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
