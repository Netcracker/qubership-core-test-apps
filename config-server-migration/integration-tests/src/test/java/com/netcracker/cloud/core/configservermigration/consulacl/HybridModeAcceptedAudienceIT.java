package com.netcracker.cloud.core.configservermigration.consulacl;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core-legacy-api in the Consul auth mode {@code hybrid}, where the auth method accepts the audience
 * {@value ConsulAclStand#AUDIENCE}, as Consul is installed: the bootstrap deletes the static token secret a
 * {@code legacy} deployment left, and the service logs in instead. With the secret gone, a write the service completes
 * was made with the token of that login.
 */
@EnableExtension
@Tag("core-legacy-api-consul-acl")
class HybridModeAcceptedAudienceIT {

    private static final String PROFILE = "hybrid-accepted";

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private ConsulAclStand stand;
    private ConsulApi admin;

    @BeforeAll
    void deploy() {
        stand = new ConsulAclStand(kubernetes, consulUrl, true);
        admin = stand.consulAsAdmin();
        List<String> audiences = AuthMethod.boundAudiences(admin);
        assertTrue(audiences.contains(ConsulAclStand.AUDIENCE),
                "BoundAudiences of " + ConsulAclStand.AUTH_METHOD + " as Consul is installed: " + audiences);
        stand.uninstall();
        stand.createStaticTokenSecret();
        stand.deploy("hybrid");
    }

    @AfterAll
    void close() {
        if (stand != null) {
            stand.close();
        }
    }

    @Test
    void theBootstrapDeletesTheStaticTokenSecret() {
        assertNull(stand.staticTokenSecret(), "secret " + ConsulAclStand.STATIC_TOKEN_SECRET);
    }

    @Test
    void aPropertyWrittenThroughTheServiceIsStoredInConsul() {
        String path = "/" + ConsulAclStand.APPLICATION + "/" + PROFILE;
        Http.Response response = stand.coreLegacyApi().post(path, Map.of("acl.probe", "written in hybrid"));

        assertEquals(201, response.status(), "POST " + path + " answered " + response.body());
        assertEquals("written in hybrid", ConsulAclStand.storedValue(admin, PROFILE, "acl.probe"),
                "acl.probe in Consul");
    }
}
