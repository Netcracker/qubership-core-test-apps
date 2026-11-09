package com.netcracker.cloud.core.configservermigration.consulacl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends requests to the Consul HTTP API with one ACL token, or with none. A failure names the request and the status
 * only: the bodies of ACL calls carry tokens.
 */
final class ConsulApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final URI baseUri;
    private final String token;

    ConsulApi(URL baseUrl, String token) {
        this.baseUri = URI.create(baseUrl.toString());
        this.token = token;
    }

    /** The same Consul, with another token. */
    ConsulApi withToken(String otherToken) {
        try {
            return new ConsulApi(baseUri.toURL(), otherToken);
        } catch (IOException e) {
            throw new IllegalStateException("invalid Consul URL " + baseUri, e);
        }
    }

    Response get(String path) {
        return send(request(path).GET());
    }

    Response put(String path, String body) {
        return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    Response post(String path, String body) {
        return send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(30));
        if (token != null && !token.isEmpty()) {
            builder.header("X-Consul-Token", token);
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        String name = request.method() + " " + request.uri().getPath();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(name, response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(name + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " interrupted", e);
        }
    }

    record Response(String request, int status, String body) {

        Response requireSuccess() {
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(request + " failed with HTTP " + status);
            }
            return this;
        }

        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(request + " returned a body that is not JSON", e);
            }
        }
    }
}
