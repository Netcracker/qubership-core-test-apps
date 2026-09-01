package com.netcracker.cloud.core.consullogin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class ConsulClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String token;

    ConsulClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    Response get(String path) {
        return send(request(path).GET());
    }

    Response put(String path, String body) {
        return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    Response delete(String path) {
        return send(request(path).DELETE());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null && !token.isEmpty()) {
            builder.header("X-Consul-Token", token);
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Consul request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consul request interrupted", e);
        }
    }

    record Response(int status, String body) {

        boolean isSuccessful() {
            return status >= 200 && status < 300;
        }

        Response requireSuccess(String action) {
            if (!isSuccessful()) {
                throw new IllegalStateException(action + " failed with HTTP " + status);
            }
            return this;
        }
    }
}
