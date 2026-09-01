package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;

import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;

final class Stand {

    static final String CONSUL_NAMESPACE = "consul";
    static final String CONSUL_SERVER_POD_PREFIX = "consul-consul-server";
    static final String CONSUL_IN_CLUSTER_URL = "http://consul-consul-server.consul.svc:8500";
    static final String AUDIENCE = "netcracker";
    static final String TOKEN_MOUNT_PATH = "/var/run/secrets/tokens/netcracker";

    private Stand() {
    }

    static KubernetesClient newKubernetesClient() {
        Config config = Config.autoConfigure(null);
        config.setTrustCerts(true);
        config.setDisableHostnameVerification(true);
        String master = System.getProperty("kubernetes.master");
        if (master != null && !master.isBlank()) {
            config.setMasterUrl(master);
        }
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    static LocalPortForward forwardConsulPort(KubernetesClient kubernetes) {
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

    static String readBootstrapToken(KubernetesClient kubernetes) {
        Secret secret = findSecret(kubernetes,
                candidate -> candidate.getMetadata().getName().endsWith("bootstrap-acl-token"),
                "no bootstrap ACL token secret in namespace " + CONSUL_NAMESPACE);
        return decode(secret, "token");
    }

    static ObjectNode authMethodConfig(KubernetesClient kubernetes, ObjectNode config) {
        Secret reviewer = findSecret(kubernetes,
                candidate -> candidate.getMetadata().getName().endsWith("auth-method")
                        && "kubernetes.io/service-account-token".equals(candidate.getType()),
                "no auth method reviewer secret in namespace " + CONSUL_NAMESPACE);
        return config
                .put("Host", "https://kubernetes.default.svc")
                .put("CACert", decode(reviewer, "ca.crt"))
                .put("ServiceAccountJWT", decode(reviewer, "token"));
    }

    static Secret findSecret(KubernetesClient kubernetes, Predicate<Secret> matches, String notFoundMessage) {
        List<Secret> secrets = kubernetes.secrets().inNamespace(CONSUL_NAMESPACE).list().getItems();
        return secrets.stream()
                .filter(matches)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(notFoundMessage));
    }

    static String decode(Secret secret, String key) {
        String value = secret.getData().get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "key " + key + " is missing from secret " + secret.getMetadata().getName());
        }
        return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }
}
