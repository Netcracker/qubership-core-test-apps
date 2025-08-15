package com.netcracker.it.meshtestservicespring.model.svt;

import com.netcracker.it.meshtestservicespring.utils.CloseableUrl;
import com.netcracker.it.meshtestservicespring.utils.Utils;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class SvtTestCase {
    protected GenericKubernetesResource resource;
    protected boolean replicationSucceeded;
    protected long replicationStartTime;
    protected long replicationEndTime;

    protected KubernetesClient platformClient;

    protected List<RequestTestCase> requestTestCases;

    protected Function<String, CloseableUrl> urlProvider;

    public abstract void applyResource(final String namespace);

    public long getReplicationTime() {
        return replicationEndTime - replicationStartTime;
    }

    public boolean isPassed() {
        if (replicationSucceeded) {
            log.debug("Replication of {} with name {} succeeded", resource.getKind(), resource.getMetadata().getName());
            return requestTestCases.stream().allMatch(RequestTestCase::isSuccess);
        }
        log.error("Replication of {} with name {} failed", resource.getKind(), resource.getMetadata().getName());
        return false;
    }

    public void verifyRequests() {
        requestTestCases.forEach(requestTestCase -> {
            try {
                Utils.runWithRetry(() -> {
                    try (final CloseableUrl urlWrapper = urlProvider.apply(resource.getMetadata().getName())) {
                        requestTestCase.runTestRequest(replicationStartTime, urlWrapper.getUrl());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, TimeUnit.MINUTES.toMillis(5));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
