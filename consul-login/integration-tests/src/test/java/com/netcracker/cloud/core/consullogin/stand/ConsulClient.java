package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the Consul HTTP API with the token it was built with, if any. Responses carry the status and the body, and
 * a failure is reported by status alone: bodies of ACL calls contain tokens, and test output is not a place for them.
 */
public final class ConsulClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String token;

    public ConsulClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public Response get(String path) {
        return send(request(path).GET());
    }

    public Response put(String path, String body) {
        return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    public Response delete(String path) {
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

    public record Response(int status, String body) {

        public boolean isSuccessful() {
            return status >= 200 && status < 300;
        }

        public Response requireSuccess(String action) {
            if (!isSuccessful()) {
                throw new IllegalStateException(action + " failed with HTTP " + status);
            }
            return this;
        }

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("unexpected response body", e);
            }
        }
    }
}
