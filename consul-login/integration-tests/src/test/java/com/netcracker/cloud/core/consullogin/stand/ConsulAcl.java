package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ACL objects a scenario of the m2m way builds around itself through the Consul API: a policy over its own prefix,
 * a role that grants it, the auth method of the m2m way, and a binding rule. Also the reads of the tokens Consul
 * issued, which tell the way a pod took. The kubernetes way gets its objects from {@link ConsulAclResource} instead.
 *
 * <p>A scenario creates them while it runs and removes them after, so that scenarios can share one Consul without
 * seeing each other's tokens.
 */
public final class ConsulAcl {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConsulAcl() {
    }

    public static void createReadPolicy(ConsulClient consul, String name, String keyPrefix) {
        createKeyPrefixPolicy(consul, name, keyPrefix, "read");
    }

    private static void createKeyPrefixPolicy(ConsulClient consul, String name, String keyPrefix, String policy) {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Description", "Consul login tests: " + policy + " access to " + keyPrefix)
                .put("Rules", "key_prefix \"" + keyPrefix + "\" { policy = \"" + policy + "\" }");
        consul.put("/v1/acl/policy", body.toString()).requireSuccess("creating the policy " + name);
    }

    public static void createRole(ConsulClient consul, String name, String policy) {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Description", "Consul login tests: role granting " + policy);
        body.putArray("Policies").add(JSON.createObjectNode().put("Name", policy));
        consul.put("/v1/acl/role", body.toString()).requireSuccess("creating the role " + name);
    }

    /**
     * The auth method of the m2m way, of type {@code jwt}: the stand-in signs its own tokens, and Consul validates
     * them against the public half of the key generated for the run. {@code maxTokenTtl} may be null; Consul refuses
     * anything below a minute.
     */
    public static void createM2MAuthMethod(ConsulClient consul, String name, String publicKeyPem, String issuer,
                                           String audience, String maxTokenTtl) {
        ObjectNode config = JSON.createObjectNode().put("BoundIssuer", issuer);
        config.putArray("JWTValidationPubKeys").add(publicKeyPem);
        config.putArray("BoundAudiences").add(audience);
        config.putObject("ClaimMappings").put("sub", "sub");

        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Type", "jwt")
                .put("Description", "Consul login tests: auth method of the m2m way");
        if (maxTokenTtl != null) {
            body.put("MaxTokenTTL", maxTokenTtl);
        }
        body.set("Config", config);
        consul.put("/v1/acl/auth-method", body.toString()).requireSuccess("creating the auth method " + name);
    }

    public static String createBindingRule(ConsulClient consul, String authMethod, String selector, String role) {
        ObjectNode body = JSON.createObjectNode()
                .put("AuthMethod", authMethod)
                .put("Description", "Consul login tests: " + selector + " to " + role)
                .put("Selector", selector)
                .put("BindType", "role")
                .put("BindName", role);
        return consul.put("/v1/acl/binding-rule", body.toString())
                .requireSuccess("creating the binding rule of " + authMethod)
                .json()
                .path("ID")
                .asText();
    }

    /** How many tokens an auth method has issued. The way a pod took is read from here, not from its log. */
    public static int issuedTokenCount(ConsulClient consul, String authMethod) {
        return issuedTokens(consul, authMethod, null).size();
    }

    /**
     * How many tokens an auth method has issued with a role. An auth method that several scenarios share is told apart
     * by the role, which belongs to one namespace; a role that does not exist yet counts no tokens.
     */
    public static int issuedTokenCount(ConsulClient consul, String authMethod, String role) {
        return issuedTokens(consul, authMethod, role).size();
    }

    /**
     * When an auth method issued its newest token with a role, by the clock of Consul itself. A scenario that waits
     * for a relogin compares against this rather than against a count: the two logins a pod makes while it starts
     * would satisfy a count on their own.
     */
    public static Instant latestIssuedAt(ConsulClient consul, String authMethod, String role) {
        Instant latest = Instant.EPOCH;
        for (JsonNode token : issuedTokens(consul, authMethod, role)) {
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

    /** The tokens an auth method has issued, only those with the role when one is given. */
    private static List<JsonNode> issuedTokens(ConsulClient consul, String authMethod, String role) {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        List<JsonNode> tokens = new ArrayList<>();
        if (!response.isSuccessful()) {
            return tokens;
        }
        for (JsonNode token : response.json()) {
            if (role == null || hasRole(token, role)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean hasRole(JsonNode token, String role) {
        for (JsonNode link : token.path("Roles")) {
            if (role.equals(link.path("Name").asText())) {
                return true;
            }
        }
        return false;
    }

    public static void deleteIssuedTokens(ConsulClient consul, String authMethod) {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode token : response.json()) {
            consul.delete("/v1/acl/token/" + token.path("AccessorID").asText());
        }
    }

    public static void deleteByName(ConsulClient consul, String listPath, String deletePath, String name) {
        ConsulClient.Response response = consul.get(listPath);
        if (!response.isSuccessful()) {
            return;
        }
        for (JsonNode item : response.json()) {
            if (name.equals(item.path("Name").asText())) {
                consul.delete(deletePath + item.path("ID").asText());
            }
        }
    }

    public static void deleteRole(ConsulClient consul, String name) {
        deleteByName(consul, "/v1/acl/roles", "/v1/acl/role/", name);
    }

    public static void deletePolicy(ConsulClient consul, String name) {
        deleteByName(consul, "/v1/acl/policies", "/v1/acl/policy/", name);
    }
}
