package com.netcracker.it.meshtestservicespring.watch;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
public class IngressWatcher extends AbstractResourceWatcher<Ingress> {
    public IngressWatcher(KubernetesClient platformClient, String namespace, BiConsumer<Action, Ingress> eventConsumer) {
        super(platformClient, namespace, eventConsumer);
        initializeWatchWithRetry();
    }

    @Override
    protected Watch initializeWatch() {
        return platformClient.network().v1().ingresses()
                .inNamespace(namespace)
                .watch(this);
    }
}
