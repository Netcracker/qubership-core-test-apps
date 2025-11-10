package com.netcracker.it.spring.watch;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
public class ServiceWatcher extends AbstractResourceWatcher<Service> {
    public ServiceWatcher(KubernetesClient platformClient, String namespace, BiConsumer<Action, Service> eventConsumer) {
        super(platformClient, namespace, eventConsumer);
        initializeWatchWithRetry();
    }

    @Override
    protected Watch initializeWatch() {
        log.info("Setting up watcher on services in namespace {}", namespace);
        return platformClient.services()
                .inNamespace(namespace)
                .watch(this);
    }
}
