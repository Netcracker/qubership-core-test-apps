package com.netcracker.it.meshtestservicespring.model.svt;

import com.netcracker.it.meshtestservicespring.utils.CloseableUrl;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

@Data
@Slf4j
public class DeletionTestCase extends SvtTestCase {

    @Builder
    public DeletionTestCase(GenericKubernetesResource resource, boolean replicationSucceeded, long replicationStartTime, long replicationEndTime, KubernetesClient platformClient, List<RequestTestCase> requestTestCases, Function<String, CloseableUrl> urlProvider) {
        super(resource, replicationSucceeded, replicationStartTime, replicationEndTime, platformClient, requestTestCases, urlProvider);
    }

    @Override
    public void applyResource(final String namespace) {
        log.debug("Deleting {}/{}", resource.getApiVersion(), resource.getKind());
        setReplicationStartTime(System.currentTimeMillis());
        platformClient.genericKubernetesResources(resource.getApiVersion(), resource.getKind())
                .inNamespace(namespace)
                .resource(resource)
                .delete();
    }
}
