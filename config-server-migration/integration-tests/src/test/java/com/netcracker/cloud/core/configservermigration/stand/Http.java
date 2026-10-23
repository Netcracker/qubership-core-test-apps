package com.netcracker.cloud.core.configservermigration.stand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

/** Sends requests to one HTTP API, config-server's or Consul's, and returns the status and the body as they came. */
public final class Http {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final URI baseUri;

    public Http(URL baseUrl) {
        this.baseUri = URI.create(baseUrl.toString());
    }

    /** Waits until the service answers {@code GET path} with 200; {@code name} is for the failure message. */
    public void awaitOk(String name, String path) {
        await(name + " answers GET " + path)
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> get(path).status() == 200);
    }

    public Response get(String path) {
        return send(request(path).GET());
    }

    public Response post(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json").POST(json(body)));
    }

    public Response put(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json").PUT(json(body)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(30));
    }

    private static HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize the request body", e);
        }
    }

    private Response send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(request.method() + " " + request.uri() + " interrupted", e);
        }
    }

    public record Response(int status, String body) {
    }
}
