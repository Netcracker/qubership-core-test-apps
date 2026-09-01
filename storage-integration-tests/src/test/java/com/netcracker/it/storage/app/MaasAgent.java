package com.netcracker.it.storage.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The MaaS reconciliation endpoint, reached through maas-agent.
 *
 * <p>maas-agent proxies every /api path to maas-service under its own agent identity, so this
 * needs no separate account. Recreating a registered topic that vanished from the broker is
 * deliberately not part of get-or-create — a topic may have been deleted on purpose, and silently
 * recreating it would hide that. It is an explicit operation, and this is it.
 *
 * <p>Reached through the API server's service proxy rather than a port-forward: a forward is
 * pinned to one pod, and the scenario that kills maas-agent instances kills that very pod.
 */
public class MaasAgent {

    private static final Logger log = LoggerFactory.getLogger(MaasAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** One topic in the reconciliation report; {@code status} is added, exists, error or not_found. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SyncReport(String name, String status, String errMsg) {
    }

    private final KubernetesClient client;
    private final String namespace;
    private final String service;

    public MaasAgent(KubernetesClient client, String namespace, String service) {
        this.client = client;
        this.namespace = namespace;
        this.service = service;
    }

    /**
     * Recreates on the broker every topic the namespace has registered but the broker has lost.
     * The call answers 200 with a per-topic report, so a topic that could not be recreated has to
     * be read out of the body — otherwise it resurfaces later as an unexplained MAAS-0600.
     */
    public void recoverTopics() {
        String path = String.format("/api/v1/namespaces/%s/services/%s:8080/proxy/api/v2/kafka/recovery/%s",
                namespace, service, namespace);
        String body;
        try {
            body = client.raw(path, "POST", null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("POST " + path + " failed: " + e, e);
        }

        List<SyncReport> failed = parse(body).stream()
                .filter(report -> "error".equals(report.status()))
                .toList();
        if (!failed.isEmpty()) {
            throw new IllegalStateException("MaaS could not reconcile " + failed.size()
                    + " topic(s) of namespace " + namespace + ": " + failed);
        }
        log.info("MaaS topic reconciliation for {}: {}", namespace, body);
    }

    private static List<SyncReport> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("MaaS returned an empty reconciliation report");
        }
        try {
            return MAPPER.readValue(body, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("unreadable reconciliation report: " + body, e);
        }
    }
}
