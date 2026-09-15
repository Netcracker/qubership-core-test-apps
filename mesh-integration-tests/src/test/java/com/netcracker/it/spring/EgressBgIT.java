package com.netcracker.it.spring;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.common.model.EgressBgEchoResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.netcracker.it.common.HttpClient.okHttpClient;
import static com.netcracker.it.spring.Const.EGRESS_GW_SERVICE_NAME;
import static com.netcracker.it.spring.Const.PUBLIC_GW_SERVICE_NAME;
import static com.netcracker.it.spring.Const.SPRING_SERVICE_NAME;
import static com.netcracker.it.spring.Const.X_VERSION_NAME_HEADER;
import static com.netcracker.it.spring.Const.X_VERSION_NAME_VALUE_ACTIVE;
import static com.netcracker.it.spring.Const.X_VERSION_NAME_VALUE_CANDIDATE;
import static com.netcracker.it.spring.Const.X_VERSION_NAME_VALUE_LEGACY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Egress endpoints with Blue/Green support.
 *
 * <p>An application in a Blue/Green domain calls the egress gateway by its explicit
 * address on a path prefix, and the gateway picks the outbound endpoint from the
 * {@code x-version-name} header the application propagated: {@code candidate} and
 * {@code legacy} go to a stub, {@code active} and no header go to production. The
 * tests are mesh-agnostic on purpose: the same assertions have to hold for the
 * Cloud-Core Mesh {@code RouteConfiguration} in {@code EgressBg.yaml} and for the
 * Istio {@code HTTPRoute} in {@code EgressBg-istio.yaml} it migrates to.
 *
 * <p>Both endpoints are the egress-bg-echo nginx, behind one Service each. It answers
 * with the name of the endpoint that was reached, which is what tells the test which
 * one the gateway chose, and with the {@code x-version-name} it received, which shows
 * the header survived the hop.
 */
@EnableExtension
@Slf4j
@Tag("Mesh")
public class EgressBgIT {

    private static final String INTEGRATION_PATH = "egress-bg/integration/hello";
    private static final String STUB_ONLY_PATH = "egress-bg/stub-only/hello";
    private static final String PRODUCTION_ENDPOINT = "bg-prod";
    private static final String STUB_ENDPOINT = "bg-stub";

    private static final String VIA_SPRING_PROXY =
            "api/v1/" + SPRING_SERVICE_NAME + "/spring/proxy?url=" + EGRESS_GW_SERVICE_NAME + ":8080/";

    private static final Gson GSON = new Gson();

    private static final OkHttpClient noRetryClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .build();

    @PortForward(serviceName = @Value(EGRESS_GW_SERVICE_NAME))
    private static URL egressGWServerUrl;

    @PortForward(serviceName = @Value(PUBLIC_GW_SERVICE_NAME))
    private static URL publicGWServerUrl;

    @BeforeAll
    public static void init() {
        assertNotNull(egressGWServerUrl);
        assertNotNull(publicGWServerUrl);
    }

    static Stream<Arguments> versionNames() {
        return Stream.of(
                Arguments.of(null, PRODUCTION_ENDPOINT),
                Arguments.of(X_VERSION_NAME_VALUE_ACTIVE, PRODUCTION_ENDPOINT),
                Arguments.of(X_VERSION_NAME_VALUE_CANDIDATE, STUB_ENDPOINT),
                Arguments.of(X_VERSION_NAME_VALUE_LEGACY, STUB_ENDPOINT));
    }

    @ParameterizedTest(name = "[{index}] x-version-name={0} -> {1}")
    @MethodSource("versionNames")
    public void testEgressGatewaySelectsEndpointByVersionName(String xVersionName, String expectedEndpoint) throws IOException {
        EgressBgEchoResponse echo = callEgress(egressGWServerUrl, INTEGRATION_PATH, xVersionName);

        assertEquals(expectedEndpoint, echo.getEndpoint(),
                "x-version-name=" + xVersionName + " was routed to the wrong endpoint");
        assertEquals("/hello", echo.getUri(), "prefix rewrite did not strip the egress path prefix");
        assertEquals(headerAsSeenByEndpoint(xVersionName), echo.getXVersionName(),
                "x-version-name did not reach the endpoint unchanged");
    }

    @ParameterizedTest(name = "[{index}] x-version-name={0} -> {1}")
    @MethodSource("versionNames")
    public void testVersionNameSurvivesTheApplicationHop(String xVersionName, String expectedEndpoint) throws IOException {
        EgressBgEchoResponse echo = callEgress(publicGWServerUrl, VIA_SPRING_PROXY + INTEGRATION_PATH, xVersionName);

        assertEquals(expectedEndpoint, echo.getEndpoint(),
                "x-version-name=" + xVersionName + " sent through the application was routed to the wrong endpoint");
        assertEquals(headerAsSeenByEndpoint(xVersionName), echo.getXVersionName(),
                "x-version-name did not survive the application hop unchanged");
    }

    @ParameterizedTest(name = "[{index}] x-version-name={0}")
    @NullSource
    @ValueSource(strings = X_VERSION_NAME_VALUE_ACTIVE)
    public void testProductionTrafficIsRejectedWhenProductionEndpointIsOff(String xVersionName) throws IOException {
        Request request = requestBuilder(egressGWServerUrl, STUB_ONLY_PATH, xVersionName).build();
        try (Response response = noRetryClient.newCall(request).execute()) {
            String body = response.body().string();
            log.info("Stub-only integration answered {} to x-version-name={}: {}", response.code(), xVersionName, body);
            assertNotEquals(200, response.code(),
                    "x-version-name=" + xVersionName + " matched a route although the production endpoint is off: " + body);
            assertFalse(body.contains("\"endpoint\":\"" + PRODUCTION_ENDPOINT + "\""),
                    "x-version-name=" + xVersionName + " reached production although the production endpoint is off: " + body);
            assertFalse(body.contains("\"endpoint\":\"" + STUB_ENDPOINT + "\""),
                    "x-version-name=" + xVersionName + " reached the stub, which is for candidate and legacy only: " + body);
        }
    }

    @ParameterizedTest(name = "[{index}] x-version-name={0}")
    @ValueSource(strings = {X_VERSION_NAME_VALUE_CANDIDATE, X_VERSION_NAME_VALUE_LEGACY})
    public void testStubStaysReachableWhenProductionEndpointIsOff(String xVersionName) throws IOException {
        EgressBgEchoResponse echo = callEgress(egressGWServerUrl, STUB_ONLY_PATH, xVersionName);

        assertEquals(STUB_ENDPOINT, echo.getEndpoint(),
                "x-version-name=" + xVersionName + " was not routed to the stub");
        assertEquals(xVersionName, echo.getXVersionName());
    }

    private static String headerAsSeenByEndpoint(String xVersionName) {
        return xVersionName == null ? "" : xVersionName;
    }

    private EgressBgEchoResponse callEgress(URL baseUrl, String path, String xVersionName) throws IOException {
        Request request = requestBuilder(baseUrl, path, xVersionName).build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            assertEquals(200, response.code(), "egress call to " + request.url() + " failed: " + body);
            log.info("Egress endpoint answered x-version-name={}: {}", xVersionName, body);
            return GSON.fromJson(body, EgressBgEchoResponse.class);
        }
    }

    private Request.Builder requestBuilder(URL baseUrl, String path, String xVersionName) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .get();
        if (xVersionName != null) {
            builder.addHeader(X_VERSION_NAME_HEADER, xVersionName);
        }
        return builder;
    }
}
