package com.netcracker.it.storage.app;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * The MaaS reconciliation endpoint, reached through maas-agent.
 *
 * <p>maas-agent proxies every /api path to maas-service under its own agent identity, so this
 * needs no separate account. Recreating a registered topic that vanished from the broker is
 * deliberately not part of get-or-create — a topic may have been deleted on purpose, and silently
 * recreating it would hide that. It is an explicit operation, and this is it.
 */
public class MaasAgent {

    private static final Logger log = LoggerFactory.getLogger(MaasAgent.class);

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final HttpUrl baseUrl;

    public MaasAgent(URL baseUrl) {
        this.baseUrl = HttpUrl.get(baseUrl.toString());
    }

    /** Recreates on the broker every topic the namespace has registered but the broker has lost. */
    public void recoverTopics(String namespace) {
        HttpUrl url = baseUrl.newBuilder()
                .addPathSegments("api/v2/kafka/recovery")
                .addPathSegment(namespace)
                .build();
        Request request = new Request.Builder().url(url)
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("POST " + url + " returned " + response.code() + ": " + body);
            }
            log.info("MaaS topic reconciliation for {}: {}", namespace, body);
        } catch (IOException e) {
            throw new IllegalStateException("POST " + url + " failed: " + e, e);
        }
    }
}
