package com.netcracker.it.spring.model.svt;

import com.netcracker.it.spring.utils.CloseableUrl;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

@Data
@Slf4j
public class ReplicationTestCase extends SvtTestCase {
    @Builder
    public ReplicationTestCase(GenericKubernetesResource resource, boolean replicationSucceeded, long replicationStartTime, long replicationEndTime, KubernetesClient platformClient, List<RequestTestCase> requestTestCases, Function<String, CloseableUrl> urlProvider) {
        super(resource, replicationSucceeded, replicationStartTime, replicationEndTime, platformClient, requestTestCases, urlProvider);
    }

    @Override
    public void applyResource(String namespace) {
        log.debug("Applying {}/{}", resource.getApiVersion(), resource.getKind());
        setReplicationStartTime(System.currentTimeMillis());
        platformClient.genericKubernetesResources(resource.getApiVersion(), resource.getKind())
                .inNamespace(namespace)
                .resource(resource)
                .create();
    }
}
