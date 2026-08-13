package com.netcracker.it.storage.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Client for the application contract; measurements come from the app, not from the runner. */
public class StorageTestApp {

    private static final Logger log = LoggerFactory.getLogger(StorageTestApp.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final HttpUrl baseUrl;

    public StorageTestApp(URL baseUrl) {
        this.baseUrl = HttpUrl.get(baseUrl.toString());
    }

    public void initStorage(String storage) {
        post(url("api", "v1", "db", storage, "init").build());
    }

    public void startWorkload(String storage, String handleMode, int operationsPerSecond) {
        post(url("api", "v1", "workload", "start")
                .addQueryParameter("storage", storage)
                .addQueryParameter("handleMode", handleMode)
                .addQueryParameter("operationsPerSecond", Integer.toString(operationsPerSecond))
                .build());
        log.info("Workload started against {} in {} mode", storage, handleMode);
    }

    public void stopWorkload() {
        post(url("api", "v1", "workload", "stop").build());
    }

    public WorkloadStats stats() {
        return read(url("api", "v1", "workload", "stats").build(), WorkloadStats.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> diag() {
        return read(url("api", "v1", "diag").build(), Map.class);
    }

    private HttpUrl.Builder url(String... segments) {
        HttpUrl.Builder builder = baseUrl.newBuilder();
        for (String segment : segments) {
            builder.addPathSegment(segment);
        }
        return builder;
    }

    private void post(HttpUrl url) {
        Request request = new Request.Builder().url(url)
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("POST " + url + " returned " + response.code()
                        + ": " + bodyOf(response));
            }
        } catch (IOException e) {
            throw new IllegalStateException("POST " + url + " failed", e);
        }
    }

    private <T> T read(HttpUrl url, Class<T> type) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            String body = bodyOf(response);
            if (!response.isSuccessful()) {
                throw new IllegalStateException("GET " + url + " returned " + response.code() + ": " + body);
            }
            return MAPPER.readValue(body, type);
        } catch (IOException e) {
            throw new IllegalStateException("GET " + url + " failed", e);
        }
    }

    private static String bodyOf(Response response) throws IOException {
        return response.body() == null ? "" : response.body().string();
    }
}
