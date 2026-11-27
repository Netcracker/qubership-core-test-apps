package com.netcracker.it.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.netcracker.it.common.HttpClient.okHttpClient;

public class ConfigServerClient {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String configServerUrl;

    public ConfigServerClient(String configServerUrl) {
        this.configServerUrl = configServerUrl;
    }

    public void writeGlobalProperty(String key, String value) throws IOException {
        post("global/default", Map.of(key, value));
    }

    public void deleteGlobalProperty(String key) throws IOException {
        post("global/default/properties-delete", List.of(key));
    }

    private void post(String path, Object body) throws IOException {
        Request request = new Request.Builder()
                .url(configServerUrl + path)
                .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("POST /" + path + " failed: HTTP " + response.code() + ": "
                        + (response.body() == null ? "" : response.body().string()));
            }
        }
    }
}
