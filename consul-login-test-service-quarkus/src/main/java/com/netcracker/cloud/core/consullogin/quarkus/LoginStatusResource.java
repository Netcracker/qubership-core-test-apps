package com.netcracker.cloud.core.consullogin.quarkus;

import com.netcracker.cloud.consul.provider.common.TokenStorage;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reports what the login left behind, in the shape the scenarios read: which way the service was configured for,
 * whether it holds a Consul token, and the property it got from Consul. The token itself never leaves the pod — only
 * a fingerprint of it, which is enough to tell one token from another.
 */
@Path("/login-status")
public class LoginStatusResource {

    @Inject
    TokenStorage tokenStorage;

    @ConfigProperty(name = "quarkus.consul-source-config.login.mode")
    Optional<String> loginMode;

    @ConfigProperty(name = "quarkus.consul-source-config.login.auth-method")
    Optional<String> authMethod;

    @ConfigProperty(name = "quarkus.consul-source-config.login.audience")
    Optional<String> audience;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> loginStatus() {
        String token = tokenStorage.get();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("loginMode", loginMode.orElse("UNSET"));
        status.put("authMethod", authMethod.orElse("UNSET"));
        status.put("audience", audience.orElse("UNSET"));
        status.put("tokenPresent", token != null && !token.isEmpty());
        status.put("tokenFingerprint", fingerprint(token));
        status.put("consulMarker", marker());
        return status;
    }

    /** Read on every call rather than injected once, so that a value changed in Consul later is visible here. */
    private static String marker() {
        return ConfigProvider.getConfig().getOptionalValue("service.marker", String.class).orElse("ABSENT");
    }

    static String fingerprint(String token) {
        if (token == null || token.isEmpty()) {
            return "NONE";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
