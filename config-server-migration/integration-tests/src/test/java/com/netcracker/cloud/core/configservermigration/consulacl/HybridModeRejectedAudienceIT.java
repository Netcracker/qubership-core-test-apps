package com.netcracker.cloud.core.configservermigration.consulacl;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.cloud.core.configservermigration.stand.Cluster;
import com.netcracker.cloud.core.configservermigration.stand.Http;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core-legacy-api in the Consul auth mode {@code hybrid}, where the auth method does not accept the audience
 * {@value ConsulAclStand#AUDIENCE}: the bootstrap issues the static token, and the service works with it.
 *
 * <p>The test removes the audience from the auth method for the time of the class and writes the method back after.
 * With the audience gone, a login of the service fails, so a write the service completes was made with the static
 * token.
 */
@EnableExtension
@Tag("core-legacy-api-consul-acl")
class HybridModeRejectedAudienceIT {

    private static final String PROFILE = "hybrid-rejected";

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private ConsulAclStand stand;
    private ConsulApi admin;
    private JsonNode installedAuthMethod;

    @BeforeAll
    void deploy() {
        stand = new ConsulAclStand(kubernetes, consulUrl, true);
        admin = stand.consulAsAdmin();
        installedAuthMethod = AuthMethod.read(admin);
        List<String> audiences = new ArrayList<>(AuthMethod.boundAudiences(admin));
        audiences.remove(ConsulAclStand.AUDIENCE);
        audiences.add("consul-acl-it-other-audience");
        AuthMethod.writeBoundAudiences(admin, installedAuthMethod, audiences);
        stand.uninstall();
        stand.deploy("hybrid");
    }

    @AfterAll
    void restore() {
        if (installedAuthMethod != null) {
            AuthMethod.write(admin, installedAuthMethod);
        }
        if (stand != null) {
            stand.close();
        }
    }

    @Test
    void theBootstrapIssuesAStaticTokenThatMayEditTheConfigOfTheNamespace() {
        JsonNode token = admin.withToken(stand.staticToken()).get("/v1/acl/token/self").requireSuccess().json();
        List<String> policies = new ArrayList<>();
        token.path("Policies").forEach(policy -> policies.add(policy.path("Name").asText()));

        assertTrue(policies.contains(ConsulAclStand.NAMESPACE + "_config-edit"),
                "policies of the token in secret " + ConsulAclStand.STATIC_TOKEN_SECRET + ": " + policies);
    }

    @Test
    void aPropertyWrittenThroughTheServiceIsStoredInConsul() {
        String path = "/" + ConsulAclStand.APPLICATION + "/" + PROFILE;
        Http.Response response = stand.coreLegacyApi().post(path, Map.of("acl.probe", "written with the static token"));

        assertEquals(201, response.status(), "POST " + path + " answered " + response.body());
        assertEquals("written with the static token", ConsulAclStand.storedValue(admin, PROFILE, "acl.probe"),
                "acl.probe in Consul");
    }
}
