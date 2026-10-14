package com.netcracker.it.spring.envoy;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static com.netcracker.it.spring.envoy.Paths.HELLO_PATH;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableExtension
@Slf4j
@Tag("EnvoyFilter")
class EnvoyXEnvoyHeadersIT extends BaseTest {

    private static final String SERVER = "server";
    private static final String X_ENVOY = "x-envoy";
    private static final String X_ENVOY_UPSTREAM_SERVICE_TIME = "x-envoy-upstream-service-time";

    // Exception made for header X_ENVOY_UPSTREAM_SERVICE_TIME - as it was not suppressed in Cloud Core mesh
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressUrls")
    void testNoXEnvoyHeadersInResponse(String gatewayName, URL baseUrl) throws IOException {
        try (Response response = executeGetRequest(baseUrl, HELLO_PATH, Collections.emptyMap())) {
            List<String> envoyHeaders = response.headers().names().stream()
                    .filter(name -> name.toLowerCase().startsWith(X_ENVOY)
                            && !name.toLowerCase().equals(X_ENVOY_UPSTREAM_SERVICE_TIME))
                    .toList();
            assertTrue(envoyHeaders.isEmpty(), "Found unexpected x-envoy headers: " + envoyHeaders);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("ingressUrls")
    void testServerHeaderAbsent(String gatewayName, URL baseUrl) throws IOException {
        try (Response response = executeGetRequest(baseUrl, HELLO_PATH, Collections.emptyMap())) {
            assertNull(response.headers().get(SERVER),
                    "Header '%s' should be suppressed but was found".formatted(SERVER));
        }
    }

}
