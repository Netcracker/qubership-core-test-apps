package com.netcracker.it.common;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.IntValue;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.PortForward;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Value;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.common.HttpClient.okHttpClient;
import static org.awaitility.Awaitility.await;

public abstract class ConsulITBase {
    protected static ConsulClient consulClient;

    @PortForward(serviceName = @Value("consul-consul-server"), port = @IntValue(8500), cloud = @Cloud(namespace = @Value(value = "consul")))
    private static URL consulUrl;

    private static final String CONSUL_TEST_PROPERTY_PATH = "config/core/application/consul/test/property";
    private static final String SERVICE_TEST_PROPERTY_PATH = "api/v1/config";

    @BeforeAll
    static void beforeAll() {
        consulClient = new ConsulClient(consulUrl.toString());
    }

    @AfterEach
    void cleanup() throws Exception {
        consulClient.deleteProperty(CONSUL_TEST_PROPERTY_PATH);
    }

    @Test
    void testConsulIntegrationReadValue() throws Exception {
        String consulPropertyValue = "test-property-" + new Random().nextInt(0, 1000);
        consulClient.writeProperty(CONSUL_TEST_PROPERTY_PATH, consulPropertyValue);

        Request request = new Request.Builder()
                .url(URI.create(getServiceUrl() + SERVICE_TEST_PROPERTY_PATH).toURL())
                .get()
                .build();

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try (Response response = okHttpClient.newCall(request).execute()) {
                        return response.code() == 200
                                && response.body() != null
                                && consulPropertyValue.equals(response.body().string());
                    }
                });
    }

    protected abstract URL getServiceUrl();
}