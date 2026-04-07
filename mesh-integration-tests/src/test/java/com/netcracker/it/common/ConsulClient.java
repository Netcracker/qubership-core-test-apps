package com.netcracker.it.common;

import okhttp3.*;

public class ConsulClient {

    private final String consulUrl;
    private final OkHttpClient httpClient;

    public ConsulClient(String consulUrl) {
        this.consulUrl = consulUrl;
        this.httpClient = new OkHttpClient.Builder().build();
    }

    public void writeProperty(String key, String value) throws Exception {
        Request request = new Request.Builder()
                .url(consulUrl + "v1/kv/" + key)
                .put(RequestBody.create(value, MediaType.get("text/plain")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to write key '" + key + "': HTTP " + response.code());
            }
        }
    }

    public void deleteProperty(String key) throws Exception {
        Request request = new Request.Builder()
                .url(consulUrl + "v1/kv/" + key)
                .delete()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to delete key '" + key + "': HTTP " + response.code());
            }
        }
    }
}