package com.netcracker.cloud.storagetestservice.storage;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.VHost;
import com.netcracker.cloud.storagetestservice.workload.HandleMode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The other half of MaaS. A vhost is obtained exactly the way a topic is, through maas-agent to
 * maas-service to its database, so the same leader change is visible on this path too.
 */
public class MaasRabbitProbe implements StorageProbe {

    private final MaaSAPIClient maas;

    /** Resolved once at startup for LONG_HELD, the way a service that wires a client at boot holds it. */
    private volatile RabbitMaaSClient heldClient;

    public MaasRabbitProbe(MaaSAPIClient maas) {
        this.maas = maas;
    }

    @Override
    public String type() {
        return "maas-rabbit";
    }

    @Override
    public void init() {
        rabbitClient(HandleMode.PER_CALL).getOrCreateVirtualHost(classifier("probe"));
    }

    /** One operation is a get-or-create, idempotent and covering the whole MaaS path. */
    @Override
    public String writeAndRead(HandleMode handleMode, String key, String value) {
        VHost vhost = rabbitClient(handleMode).getOrCreateVirtualHost(classifier(key));
        return vhost == null ? null : value;
    }

    @Override
    public String read(HandleMode handleMode, String key) {
        VHost vhost = rabbitClient(handleMode).getVirtualHost(classifier(key));
        return vhost == null ? null : vhost.getCnn();
    }

    @Override
    public void releaseHeldHandle() {
        heldClient = null;
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("holdsClient", heldClient != null);
        return diagnostics;
    }

    private RabbitMaaSClient rabbitClient(HandleMode handleMode) {
        if (handleMode == HandleMode.PER_CALL) {
            return maas.getRabbitClient();
        }
        if (heldClient == null) {
            synchronized (this) {
                if (heldClient == null) {
                    heldClient = maas.getRabbitClient();
                }
            }
        }
        return heldClient;
    }

    /** A stable name per key, so repeated operations reuse the same vhost. */
    private static Classifier classifier(String key) {
        return new Classifier("storage-probe-" + key);
    }
}
