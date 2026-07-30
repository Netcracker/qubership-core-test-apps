package com.netcracker.it.spring.envoy;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.provider.Arguments;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.netcracker.it.spring.envoy.Paths.COMPOSITE_SERVICE_BASE_PATH;
import static com.netcracker.it.spring.envoy.Paths.PROXY_PATH;
import static com.netcracker.it.spring.Const.PRIVATE_GW_SERVICE_NAME;
import static com.netcracker.it.spring.Const.PUBLIC_GW_SERVICE_NAME;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public abstract class BaseTest {

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    protected static URL publicGWServerUrl;

    @PortForward(serviceName = @Value(PRIVATE_GW_SERVICE_NAME))
    protected static URL privateGWServerUrl;

    @BeforeAll
    static void init() {
        assertNotNull(publicGWServerUrl, "publicGWServerUrl must be initialized");
        assertNotNull(privateGWServerUrl, "privateGWServerUrl must be initialized");
    }

    static Stream<Arguments> ingressUrls() {
        return Stream.of(
                Arguments.of("public-gateway", publicGWServerUrl),
                Arguments.of("private-gateway", privateGWServerUrl)
        );
    }

    static Stream<Arguments> meshGatewayViaSpringProxy() throws MalformedURLException, URISyntaxException {
        return Stream.of(
                Arguments.of("public-gateway-to-spring-proxy-service",
                        URL.of(new URI(publicGWServerUrl.toURI() + PROXY_PATH + COMPOSITE_SERVICE_BASE_PATH), null))
        );
    }

    static Stream<Arguments> ingressAndMeshGateways() throws URISyntaxException, MalformedURLException {
        return Stream.concat(ingressUrls(), meshGatewayViaSpringProxy());
    }

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(130, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .build();

    Response executeGetRequest(URL baseUrl, String path, Map<String, String> requestHeaders) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .headers(Headers.of(requestHeaders))
                .get()
                .build();

        return okHttpClient.newCall(request).execute();
    }
}
