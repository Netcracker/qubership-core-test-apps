package com.netcracker.cloud.core.configservermigration.stand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the service behind the Service {@code config-server} answered to a fixed list of GET requests, and what the
 * Consul KV held under {@code config/<namespace>/} at the same time. Bodies are kept as they came; they are made
 * comparable only when two snapshots are compared.
 *
 * @param responses the response to each request, by its path and query
 * @param kv the value of each key, decoded as UTF-8; a key with no value maps to an empty string
 */
public record Snapshot(Map<String, Http.Response> responses, Map<String, String> kv) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Snapshot take(Http configServer, Http consul, List<String> requests) {
        Map<String, Http.Response> responses = new LinkedHashMap<>();
        for (String request : requests) {
            responses.put(request, configServer.get(request));
        }
        return new Snapshot(responses, readKv(consul));
    }

    public static String kvPrefix() {
        return "config/" + Cluster.CORE_NAMESPACE + "/";
    }

    public String toJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the snapshot", e);
        }
    }

    public static Snapshot fromJson(String json) {
        try {
            return JSON.readValue(json, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read the snapshot", e);
        }
    }

    private static Map<String, String> readKv(Http consul) {
        Http.Response response = consul.get("/v1/kv/" + kvPrefix() + "?recurse=true");
        Map<String, String> kv = new TreeMap<>();
        if (response.status() == 404) {
            return kv;
        }
        if (response.status() != 200) {
            throw new IllegalStateException("reading the Consul KV under " + kvPrefix() + " failed with HTTP "
                    + response.status());
        }
        try {
            for (JsonNode entry : JSON.readTree(response.body())) {
                JsonNode value = entry.get("Value");
                kv.put(entry.get("Key").asText(), value == null || value.isNull()
                        ? ""
                        : new String(Base64.getDecoder().decode(value.asText()), StandardCharsets.UTF_8));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unexpected Consul KV response", e);
        }
        return kv;
    }
}
