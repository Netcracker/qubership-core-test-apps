package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;

/**
 * The cluster a scenario runs against, and the Consul installed in it: a client, a tunnel to the Consul API, and the
 * two secrets that Consul installation leaves behind.
 *
 * <p>The tests run outside the cluster while the services under test run inside it, which is why every scenario works
 * through a port forward rather than through a published address.
 */
public final class Cluster {

    /** The address of Consul as a pod sees it, for the scripts and services that talk to Consul from inside. */
    public static final String CONSUL_IN_CLUSTER_URL = "http://consul-consul-server.consul.svc:8500";

    private static final String CONSUL_NAMESPACE = "consul";
    private static final String CONSUL_SERVER_POD_PREFIX = "consul-consul-server";

    private Cluster() {
    }

    /**
     * The kubeconfig of the run, with certificate checks relaxed: a kind cluster answers on an address its own
     * certificate does not name.
     */
    public static KubernetesClient newClient() {
        Config config = Config.autoConfigure(null);
        config.setTrustCerts(true);
        config.setDisableHostnameVerification(true);
        String master = System.getProperty("kubernetes.master");
        if (master != null && !master.isBlank()) {
            config.setMasterUrl(master);
        }
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    public static LocalPortForward forwardConsulPort(KubernetesClient kubernetes) {
        Pod server = kubernetes.pods().inNamespace(CONSUL_NAMESPACE).list().getItems().stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(CONSUL_SERVER_POD_PREFIX))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no pod with prefix " + CONSUL_SERVER_POD_PREFIX + " in namespace " + CONSUL_NAMESPACE));
        return kubernetes.pods()
                .inNamespace(CONSUL_NAMESPACE)
                .withName(server.getMetadata().getName())
                .portForward(8500);
    }

    /** The bootstrap token, the only one that may create ACL objects. It goes into a client and nowhere else. */
    public static String readBootstrapToken(KubernetesClient kubernetes) {
        Secret secret = findSecret(kubernetes,
                candidate -> candidate.getMetadata().getName().endsWith("bootstrap-acl-token"),
                "no bootstrap ACL token secret in namespace " + CONSUL_NAMESPACE);
        return decode(secret, "token");
    }

    /**
     * Adds to an auth method config what Consul needs to review the service account tokens of this cluster: the
     * address of the API server, its certificate, and the token of the reviewer account.
     */
    public static ObjectNode authMethodConfig(KubernetesClient kubernetes, ObjectNode config) {
        Secret reviewer = findSecret(kubernetes,
                candidate -> candidate.getMetadata().getName().endsWith("auth-method")
                        && "kubernetes.io/service-account-token".equals(candidate.getType()),
                "no auth method reviewer secret in namespace " + CONSUL_NAMESPACE);
        return config
                .put("Host", "https://kubernetes.default.svc")
                .put("CACert", decode(reviewer, "ca.crt"))
                .put("ServiceAccountJWT", decode(reviewer, "token"));
    }

    /** Removes what a scenario left in the cluster and closes what it opened, in an order that survives failures. */
    public static void tearDown(KubernetesClient kubernetes, String namespace, LocalPortForward... portForwards) {
        if (kubernetes != null && namespace != null) {
            kubernetes.namespaces().withName(namespace).delete();
        }
        for (LocalPortForward portForward : portForwards) {
            if (portForward == null) {
                continue;
            }
            try {
                portForward.close();
            } catch (IOException e) {
                // the stand is being torn down anyway
            }
        }
        if (kubernetes != null) {
            kubernetes.close();
        }
    }

    private static Secret findSecret(KubernetesClient kubernetes, Predicate<Secret> matches, String notFoundMessage) {
        List<Secret> secrets = kubernetes.secrets().inNamespace(CONSUL_NAMESPACE).list().getItems();
        return secrets.stream()
                .filter(matches)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(notFoundMessage));
    }

    private static String decode(Secret secret, String key) {
        String value = secret.getData().get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "key " + key + " is missing from secret " + secret.getMetadata().getName());
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
