package com.netcracker.cloud.core.consullogin.stand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * What an auth method needs to validate a projected service account token on its own: the keys the cluster signs those
 * tokens with, and the issuer it names in them.
 *
 * <p>Both come from the OpenID discovery of the cluster, which the run reads with the rights of its kubeconfig. Consul
 * gets the keys as static PEMs rather than as a JWKS URL, because that endpoint refuses anonymous requests and an auth
 * method has no way to present a token of its own.
 */
public final class ClusterSigningKey {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    private static final String JWKS_PATH = "/openid/v1/jwks";

    private final String issuer;
    private final List<String> publicKeysPem;

    private ClusterSigningKey(String issuer, List<String> publicKeysPem) {
        this.issuer = issuer;
        this.publicKeysPem = publicKeysPem;
    }

    public static ClusterSigningKey read(KubernetesClient kubernetes) {
        String issuer = get(kubernetes, DISCOVERY_PATH).path("issuer").asText();
        if (issuer.isEmpty()) {
            throw new IllegalStateException("the cluster names no issuer at " + DISCOVERY_PATH);
        }

        JsonNode keys = get(kubernetes, JWKS_PATH).path("keys");
        List<String> pems = new ArrayList<>();
        for (JsonNode key : keys) {
            pems.add(toPem(key));
        }
        if (pems.isEmpty()) {
            throw new IllegalStateException("the cluster publishes no signing keys at " + JWKS_PATH);
        }
        return new ClusterSigningKey(issuer, List.copyOf(pems));
    }

    public String issuer() {
        return issuer;
    }

    /** Every published key, because a cluster that is rotating one publishes the old and the new side by side. */
    public List<String> publicKeysPem() {
        return publicKeysPem;
    }

    private static JsonNode get(KubernetesClient kubernetes, String path) {
        try {
            return JSON.readTree(kubernetes.raw(path));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the cluster answered " + path + " with something other than JSON", e);
        }
    }

    /** Kubernetes signs with RSA, so a published key is its modulus and its exponent in base64url. */
    private static String toPem(JsonNode jwk) {
        BigInteger modulus = unsigned(jwk.path("n").asText());
        BigInteger exponent = unsigned(jwk.path("e").asText());
        try {
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                    .encodeToString(key.getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the signing key " + jwk.path("kid").asText() + " is not an RSA key", e);
        }
    }

    private static BigInteger unsigned(String base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }
}
