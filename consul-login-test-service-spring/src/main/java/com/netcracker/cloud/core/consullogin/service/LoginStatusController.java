package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.consul.provider.common.TokenStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class LoginStatusController {

    private final ObjectProvider<TokenStorage> tokenStorage;
    private final String transport;
    private final String consulMarker;
    private final String loginMode;
    private final String authMethod;
    private final String audience;

    public LoginStatusController(ObjectProvider<TokenStorage> tokenStorage,
                                 @Value("${service.transport:unknown}") String transport,
                                 @Value("${service.marker:ABSENT}") String consulMarker,
                                 @Value("${spring.cloud.consul.config.login.mode:UNSET}") String loginMode,
                                 @Value("${spring.cloud.consul.config.login.auth-method:UNSET}") String authMethod,
                                 @Value("${spring.cloud.consul.config.login.audience:UNSET}") String audience) {
        this.tokenStorage = tokenStorage;
        this.transport = transport;
        this.consulMarker = consulMarker;
        this.loginMode = loginMode;
        this.authMethod = authMethod;
        this.audience = audience;
    }

    @GetMapping("/login-status")
    public Map<String, Object> loginStatus() {
        String token = tokenStorage.getIfAvailable() == null ? "" : tokenStorage.getObject().get();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("transport", transport);
        status.put("loginMode", loginMode);
        status.put("authMethod", authMethod);
        status.put("audience", audience);
        status.put("tokenStorageBeanPresent", tokenStorage.getIfAvailable() != null);
        status.put("tokenPresent", token != null && !token.isEmpty());
        status.put("tokenFingerprint", fingerprint(token));
        status.put("consulMarker", consulMarker);
        return status;
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
