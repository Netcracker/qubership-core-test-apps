package com.netcracker.cloud.core.configservermigration.consulacl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and rewrites the auth method {@value ConsulAclStand#AUTH_METHOD} through the Consul API.
 *
 * <p>The Consul ACL operator writes the auth method only when it starts, so a rewrite holds until the operator
 * restarts or the test writes the method back.
 */
final class AuthMethod {

    private static final String PATH = "/v1/acl/auth-method/" + ConsulAclStand.AUTH_METHOD;

    private AuthMethod() {
    }

    static JsonNode read(ConsulApi admin) {
        return admin.get(PATH).requireSuccess().json();
    }

    static List<String> boundAudiences(ConsulApi admin) {
        List<String> audiences = new ArrayList<>();
        read(admin).path("Config").path("BoundAudiences").forEach(audience -> audiences.add(audience.asText()));
        return audiences;
    }

    /** Writes the method back as {@link #read(ConsulApi)} returned it. */
    static void write(ConsulApi admin, JsonNode method) {
        admin.put(PATH, method.toString()).requireSuccess();
    }

    /** Rewrites the method with {@code BoundAudiences} replaced and the rest of its configuration kept. */
    static void writeBoundAudiences(ConsulApi admin, JsonNode method, List<String> audiences) {
        ObjectNode changed = method.deepCopy();
        ArrayNode bound = ((ObjectNode) changed.path("Config")).putArray("BoundAudiences");
        audiences.forEach(bound::add);
        write(admin, changed);
    }
}
