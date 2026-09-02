package com.netcracker.cloud.core.consullogin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Instant;

/**
 * The ACL objects every scenario builds around itself: a policy over its own prefix, a role that grants it, an auth
 * method of the way under test, and a binding rule. Kept in one place so that a scenario reads as what differs.
 */
final class ConsulAcl {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConsulAcl() {
    }

    static void createReadPolicy(ConsulClient consul, String name, String keyPrefix) {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Description", "Consul login tests: read access to " + keyPrefix)
                .put("Rules", "key_prefix \"" + keyPrefix + "\" { policy = \"read\" }");
        consul.put("/v1/acl/policy", body.toString()).requireSuccess("creating the policy " + name);
    }

    static void createRole(ConsulClient consul, String name, String policy) {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Description", "Consul login tests: role granting " + policy);
        body.putArray("Policies").add(JSON.createObjectNode().put("Name", policy));
        consul.put("/v1/acl/role", body.toString()).requireSuccess("creating the role " + name);
    }

    static void createKubernetesAuthMethod(ConsulClient consul, KubernetesClient kubernetes, String name) {
        createKubernetesAuthMethod(consul, kubernetes, name, null);
    }

    static void createKubernetesAuthMethod(ConsulClient consul, KubernetesClient kubernetes, String name,
                                           String maxTokenTtl) {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Type", "kubernetes")
                .put("Description", "Consul login tests: Kubernetes auth method");
        if (maxTokenTtl != null) {
            body.put("MaxTokenTTL", maxTokenTtl);
        }
        body.set("Config", Stand.authMethodConfig(kubernetes, JSON.createObjectNode()));
        consul.put("/v1/acl/auth-method", body.toString()).requireSuccess("creating the auth method " + name);
    }

    /**
     * The jwt auth method of the m2m way. {@code maxTokenTtl} may be null; Consul refuses anything below a minute.
     */
    static void createJwtAuthMethod(ConsulClient consul, String name, String publicKeyPem, String issuer,
                                    String audience, String maxTokenTtl) {
        ObjectNode config = JSON.createObjectNode().put("BoundIssuer", issuer);
        config.putArray("JWTValidationPubKeys").add(publicKeyPem);
        config.putArray("BoundAudiences").add(audience);
        config.putObject("ClaimMappings").put("sub", "sub");

        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Type", "jwt")
                .put("Description", "Consul login tests: jwt auth method of the m2m way");
        if (maxTokenTtl != null) {
            body.put("MaxTokenTTL", maxTokenTtl);
        }
        body.set("Config", config);
        consul.put("/v1/acl/auth-method", body.toString()).requireSuccess("creating the auth method " + name);
    }

    static String createBindingRule(ConsulClient consul, String authMethod, String selector, String role) {
        ObjectNode body = JSON.createObjectNode()
                .put("AuthMethod", authMethod)
                .put("Description", "Consul login tests: " + selector + " to " + role)
                .put("Selector", selector)
                .put("BindType", "role")
                .put("BindName", role);
        ConsulClient.Response response = consul.put("/v1/acl/binding-rule", body.toString())
                .requireSuccess("creating the binding rule of " + authMethod);
        return Stand.readJson(response.body()).path("ID").asText();
    }

    /** How many tokens an auth method has issued. The way a pod took is read from here, not from its log. */
    static int issuedTokenCount(ConsulClient consul, String authMethod) {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        if (!response.isSuccessful()) {
            return 0;
        }
        int count = 0;
        for (JsonNode ignored : Stand.readJson(response.body())) {
            count++;
        }
        return count;
    }

    /**
     * When an auth method issued its newest token, by the clock of Consul itself. A scenario that waits for a relogin
     * compares against this rather than against a count: the two logins a pod makes while it starts would satisfy a
     * count on their own.
     */
    static Instant latestIssuedAt(ConsulClient consul, String authMethod) {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        if (!response.isSuccessful()) {
            return Instant.EPOCH;
        }
        Instant latest = Instant.EPOCH;
        for (JsonNode token : Stand.readJson(response.body())) {
            String createdAt = token.path("CreateTime").asText();
            if (createdAt.isEmpty()) {
                continue;
            }
            Instant created = Instant.parse(createdAt);
            if (created.isAfter(latest)) {
                latest = created;
            }
        }
        return latest;
    }

    static void deleteIssuedTokens(ConsulClient consul, String authMethod) {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode token : Stand.readJson(response.body())) {
            consul.delete("/v1/acl/token/" + token.path("AccessorID").asText());
        }
    }

    static void deleteByName(ConsulClient consul, String listPath, String deletePath, String name) {
        ConsulClient.Response response = consul.get(listPath);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode item : Stand.readJson(response.body())) {
            if (name.equals(item.path("Name").asText())) {
                consul.delete(deletePath + item.path("ID").asText());
            }
        }
    }

    static void deleteRole(ConsulClient consul, String name) {
        deleteByName(consul, "/v1/acl/roles", "/v1/acl/role/", name);
    }

    static void deletePolicy(ConsulClient consul, String name) {
        deleteByName(consul, "/v1/acl/policies", "/v1/acl/policy/", name);
    }
}
