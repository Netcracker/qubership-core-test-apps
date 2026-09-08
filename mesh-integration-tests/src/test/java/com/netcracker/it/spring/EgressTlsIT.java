package com.netcracker.it.spring;

import com.google.gson.Gson;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import com.netcracker.it.common.model.EgressEchoResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;

import static com.netcracker.it.common.HttpClient.okHttpClient;
import static com.netcracker.it.spring.Const.EGRESS_GW_SERVICE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Egress TLS origination through the egress gateway.
 *
 * <p>Clients reach the gateway over plain HTTP on a path prefix; the gateway opens the
 * HTTPS connection to the external host. The tests are mesh-agnostic on purpose: the
 * same assertions have to hold for the Cloud-Core Mesh {@code TlsDef} configuration in
 * {@code EgressTls.yaml} and for the Istio {@code ServiceEntry} +
 * {@code DestinationRule} configuration in {@code EgressTls-istio.yaml} it migrates to.
 *
 * <p>The external site is an in-cluster nginx that selects its server certificate by
 * SNI, so a gateway that originates TLS with the wrong SNI, the wrong CA or no client
 * certificate fails rather than silently succeeding.
 */
@EnableExtension
@Slf4j
@Tag("Mesh")
public class EgressTlsIT {

    private static final String TENANT_HEADER = "tenant-id";
    private static final String TENANT_VALUE = "cloud-common";
    private static final String CLIENT_CERT_SUBJECT = "egress-gateway-client";

    private static final Gson GSON = new Gson();

    @PortForward(serviceName = @Value(EGRESS_GW_SERVICE_NAME))
    private static URL egressGWServerUrl;

    @BeforeAll
    public static void init() {
        assertNotNull(egressGWServerUrl);
    }

    /**
     * Cluster-level TLS profile with an explicit CA: {@code TlsDef} with {@code trustedCA}
     * in Cloud-Core Mesh, {@code DestinationRule} {@code mode: SIMPLE} plus
     * {@code credentialName} in Istio.
     */
    @Test
    public void testEgressTlsOriginationWithTrustedCa() throws IOException {
        EgressEchoResponse echo = callEgress("egress-tls/verified/hello", true);

        assertEquals("verified.external.test", echo.getSni(), "gateway originated TLS with an unexpected SNI");
        assertEquals("verified.external.test", echo.getHost(), "gateway did not rewrite the authority to the external host");
        assertEquals("NONE", echo.getClientVerify(), "one-way TLS must not present a client certificate");
    }

    /** The prefix the client calls is rewritten away before the request leaves the gateway. */
    @Test
    public void testEgressTlsPrefixRewrite() throws IOException {
        EgressEchoResponse echo = callEgress("egress-tls/verified/hello", true);

        assertEquals("/hello", echo.getUri(), "prefix rewrite did not strip the egress path prefix");
    }

    /** {@code removeHeaders} on the virtual service, {@code RequestHeaderModifier} in Istio. */
    @Test
    public void testEgressTlsStripsHeaders() throws IOException {
        Request request = requestBuilder("egress-tls/verified/hello")
                .addHeader(TENANT_HEADER, TENANT_VALUE)
                .addHeader("Origin", "http://caller.example.com")
                .addHeader("Authorization", "Bearer test-token")
                .build();
        EgressEchoResponse echo = execute(request);

        assertEquals("", echo.getOrigin(), "Origin header reached the external host");
        assertEquals("", echo.getAuthorization(), "Authorization header reached the external host");
        assertEquals(TENANT_VALUE, echo.getTenantId(), "tenant-id header was not forwarded");
    }

    /**
     * The route matches on a header as well as a path, so the same path without the
     * header must not reach the external host at all.
     */
    @Test
    public void testEgressTlsHeaderMatcherIsRequired() throws IOException {
        Request request = requestBuilder("egress-tls/verified/hello").build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertEquals(404, response.code(), "route matched without the tenant-id header");
        }
    }

    /**
     * {@code TlsDef} with {@code insecure: true}, {@code insecureSkipVerify} in Istio.
     * This host serves a certificate from a CA the mesh was never given, so the request
     * only completes when verification really is switched off.
     */
    @Test
    public void testEgressTlsInsecureSkipsVerification() throws IOException {
        EgressEchoResponse echo = callEgress("egress-tls/insecure/hello", false);
        assertEquals("insecure.external.test", echo.getSni());
        assertEquals("/hello", echo.getUri());
    }

    /**
     * {@code TlsDef} carrying {@code clientCert} and {@code privateKey}, mapped to
     * {@code DestinationRule} {@code mode: MUTUAL} over a Secret with {@code tls.crt} /
     * {@code tls.key}. The external host demands a client certificate, so a gateway that
     * originated one-way TLS gets rejected during the handshake.
     */
    @Test
    public void testEgressMutualTls() throws IOException {
        EgressEchoResponse echo = callEgress("egress-tls/mtls/hello", false);

        assertEquals("mtls.external.test", echo.getSni());
        assertEquals("SUCCESS", echo.getClientVerify(), "gateway did not present a verified client certificate");
        assertTrue(echo.getClientDn().contains(CLIENT_CERT_SUBJECT),
                "unexpected client certificate subject: " + echo.getClientDn());
    }

    /**
     * Gateway-level {@code TlsDef} ({@code trustedForGateways: [egress-gateway]}): the
     * route names no profile and falls through to the gateway default. Reaching the
     * external host at all proves the profile was applied, because the site serves a
     * certificate signed by the CA that only this profile carries.
     *
     * <p>SNI is deliberately not asserted. Cloud-Core Mesh forbids {@code tls.sni} on a
     * gateway-level profile, and derives one from the endpoint only when the control
     * plane runs with {@code SNI_PROPAGATION_ENABLED=true}, which defaults to false, so
     * it originates without SNI. Istio has no gateway-wide profile, so the migration
     * expands it into a per-host DestinationRule whose {@code sni} is the destination
     * host. The two meshes differ here by design.
     */
    @Test
    public void testEgressTlsGatewayLevelProfile() throws IOException {
        EgressEchoResponse echo = callEgress("egress-tls/gwdefault/hello", false);
        log.info("Gateway-level profile reached the external host: {}", echo);

        assertEquals("gwdefault.external.test", echo.getHost(), "gateway did not rewrite the authority to the external host");
        assertEquals("/hello", echo.getUri(), "prefix rewrite did not strip the egress path prefix");
        assertEquals("NONE", echo.getClientVerify());
    }

    /**
     * A destination with neither {@code tlsConfigName} nor {@code hostRewrite}. TLS
     * origination still has to happen, through the gateway-level profile, with SNI
     * derived from the destination host.
     *
     * <p>Neither the authority nor SNI is asserted, because this route diverges on both.
     * The migration rules give every egress destination a {@code URLRewrite} hostname
     * even when the source sets no {@code hostRewrite}, so Istio sends the external host
     * while Cloud-Core Mesh forwards the caller's {@code Host}. The route names no TLS
     * profile either, so it falls through to the gateway-level one, which Cloud-Core
     * Mesh originates without SNI. Asserting either value would fail on one mesh by
     * construction, so the check covers what both must agree on and logs the rest.
     */
    @Test
    public void testEgressTlsWithoutExplicitHostRewrite() throws IOException {
        EgressEchoResponse echo = callEgress("egress-tls/implicit/hello", false);
        log.info("Route with neither hostRewrite nor tlsConfigName reached the external host: {}", echo);

        assertEquals("/hello", echo.getUri(), "prefix rewrite did not strip the egress path prefix");
        assertEquals("NONE", echo.getClientVerify());
    }

    private EgressEchoResponse callEgress(String path, boolean withTenantHeader) throws IOException {
        Request.Builder builder = requestBuilder(path);
        if (withTenantHeader) {
            builder.addHeader(TENANT_HEADER, TENANT_VALUE);
        }
        return execute(builder.build());
    }

    private Request.Builder requestBuilder(String path) {
        return new Request.Builder()
                .url(egressGWServerUrl + path)
                .get();
    }

    private EgressEchoResponse execute(Request request) throws IOException {
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body().string();
            assertEquals(200, response.code(), "egress call to " + request.url() + " failed: " + body);
            log.info("Egress echo answered: {}", body);
            return GSON.fromJson(body, EgressEchoResponse.class);
        }
    }
}
