package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Instant;

/**
 * The ACL objects every scenario builds around itself: a policy over its own prefix, a role that grants it, an auth
 * method of the way under test, and a binding rule. Kept in one place so that a scenario reads as what differs.
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

    /** A deny over a prefix beats a read granted on a shorter one, which is how a scenario walls off its own keys. */
    public static void createDenyPolicy(ConsulClient consul, String name, String keyPrefix) {
        createKeyPrefixPolicy(consul, name, keyPrefix, "deny");
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

    public static void createKubernetesAuthMethod(ConsulClient consul, KubernetesClient kubernetes, String name) {
        createKubernetesAuthMethod(consul, kubernetes, name, null);
    }

    /**
     * The auth method of the kubernetes way. Its type is {@code jwt}: Consul checks the signature of a projected token
     * against the published keys of the cluster instead of reviewing the token at the API server. A scenario therefore
     * needs neither a reviewer account nor the audience of its pods in {@code --api-audiences} of the API server.
     *
     * <p>The namespace of the pod comes from the nested claim Kubernetes puts it in, which a binding rule then reads as
     * {@code value.namespace}. {@code maxTokenTtl} may be null; Consul refuses anything below a minute.
     */
    public static void createKubernetesAuthMethod(ConsulClient consul, KubernetesClient kubernetes, String name,
                                                  String maxTokenTtl) {
        ClusterSigningKey signingKey = ClusterSigningKey.read(kubernetes);
        ObjectNode config = JSON.createObjectNode().put("BoundIssuer", signingKey.issuer());
        ArrayNode validationKeys = config.putArray("JWTValidationPubKeys");
        signingKey.publicKeysPem().forEach(validationKeys::add);
        config.putArray("BoundAudiences").add(ProjectedToken.AUDIENCE);
        config.putObject("ClaimMappings").put("/kubernetes.io/namespace", "namespace");

        createAuthMethod(consul, name, "Consul login tests: auth method of the kubernetes way", config, maxTokenTtl);
    }

    /**
     * The auth method of the m2m way, also of type {@code jwt}: the stand-in signs its own tokens, and Consul validates
     * them against the public half of the key generated for the run. {@code maxTokenTtl} may be null; Consul refuses
     * anything below a minute.
     */
    public static void createM2MAuthMethod(ConsulClient consul, String name, String publicKeyPem, String issuer,
                                           String audience, String maxTokenTtl) {
        ObjectNode config = JSON.createObjectNode().put("BoundIssuer", issuer);
        config.putArray("JWTValidationPubKeys").add(publicKeyPem);
        config.putArray("BoundAudiences").add(audience);
        config.putObject("ClaimMappings").put("sub", "sub");

        createAuthMethod(consul, name, "Consul login tests: auth method of the m2m way", config, maxTokenTtl);
    }

    private static void createAuthMethod(ConsulClient consul, String name, String description, ObjectNode config,
                                         String maxTokenTtl) {
        ObjectNode body = JSON.createObjectNode()
                .put("Name", name)
                .put("Type", "jwt")
                .put("Description", description);
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
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        if (!response.isSuccessful()) {
            return 0;
        }
        int count = 0;
        for (JsonNode ignored : response.json()) {
            count++;
        }
        return count;
    }

    /**
     * When an auth method issued its newest token, by the clock of Consul itself. A scenario that waits for a relogin
     * compares against this rather than against a count: the two logins a pod makes while it starts would satisfy a
     * count on their own.
     */
    public static Instant latestIssuedAt(ConsulClient consul, String authMethod) {
        ConsulClient.Response response = consul.get("/v1/acl/tokens?authmethod=" + authMethod);
        if (!response.isSuccessful()) {
            return Instant.EPOCH;
        }
        Instant latest = Instant.EPOCH;
        for (JsonNode token : response.json()) {
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
