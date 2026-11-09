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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * core-legacy-api in the Consul auth mode {@code k8s} on a Consul with ACLs disabled: the service works without a
 * token.
 *
 * <p>Runs on a stand of its own, where Consul is installed without ACLs and without its ACL operator, and the
 * bootstrap is not run.
 */
@EnableExtension
@Tag("core-legacy-api-consul-acls-disabled")
class ConsulAclsDisabledIT {

    private static final String PROFILE = "acls-disabled";

    @Cloud(namespace = @Value(Cluster.CORE_NAMESPACE))
    private KubernetesClient kubernetes;

    @PortForward(serviceName = @Value(Cluster.CONSUL_SERVER), port = @IntValue(8500),
            cloud = @Cloud(namespace = @Value(Cluster.CONSUL_NAMESPACE)))
    private URL consulUrl;

    private ConsulAclStand stand;

    @BeforeAll
    void deploy() {
        stand = new ConsulAclStand(kubernetes, consulUrl, false);
        stand.uninstall();
        stand.deploy("k8s");
    }

    @AfterAll
    void close() {
        if (stand != null) {
            stand.close();
        }
    }

    @Test
    void aPropertyWrittenThroughTheServiceIsStoredInConsul() {
        String path = "/" + ConsulAclStand.APPLICATION + "/" + PROFILE;
        Http.Response response = stand.coreLegacyApi().post(path, Map.of("acl.probe", "written without ACLs"));

        assertEquals(201, response.status(), "POST " + path + " answered " + response.body());
        assertEquals("written without ACLs",
                ConsulAclStand.storedValue(stand.consulWithoutToken(), PROFILE, "acl.probe"), "acl.probe in Consul");
    }
}
