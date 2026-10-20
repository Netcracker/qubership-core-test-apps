package com.netcracker.cloud.core.configservermigration.stand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Turns a response body into the form two services are compared in. Each form drops exactly the differences the
 * core-legacy-api migration guide lists as allowed for that response, and keeps everything else:
 *
 * <ul>
 *   <li>{@code /applications}: the order of applications and of their profiles;</li>
 *   <li>{@code .json} and {@code /{application}/{profile}}: the order of object keys;</li>
 *   <li>{@code .properties}: the order of lines and the trailing line break;</li>
 *   <li>{@code .yaml}: the formatting, by comparing the parsed document.</li>
 * </ul>
 */
public final class ResponseForms {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private ResponseForms() {
    }

    public static Object comparable(String request, String body) {
        String path = request.contains("?") ? request.substring(0, request.indexOf('?')) : request;
        try {
            if (path.equals("/applications")) {
                return applications(JSON.readTree(body));
            }
            if (path.endsWith(".properties")) {
                return Arrays.stream(body.split("\n"))
                        .filter(line -> !line.isBlank())
                        .collect(Collectors.toCollection(TreeSet::new));
            }
            if (path.endsWith(".yaml") || path.endsWith(".yml")) {
                return YAML.readTree(body);
            }
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot parse the response to " + request + ": " + body, e);
        }
    }

    private static Map<String, Set<String>> applications(JsonNode applications) {
        Map<String, Set<String>> byName = new TreeMap<>();
        for (JsonNode application : applications) {
            Set<String> profiles = byName.computeIfAbsent(application.get("name").asText(), name -> new TreeSet<>());
            application.get("profiles").forEach(profile -> profiles.add(profile.asText()));
        }
        return byName;
    }
}
