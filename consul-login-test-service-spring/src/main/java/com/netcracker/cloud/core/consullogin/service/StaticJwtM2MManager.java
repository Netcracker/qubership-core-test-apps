package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.M2MManager;
import com.netcracker.cloud.security.core.auth.Token;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs its own JWT instead of asking an Identity Provider for one, so that the m2m way can be exercised on a stand
 * that has no provider. Consul is configured with the matching public key, and the claims are the ones its jwt auth
 * method validates.
 *
 * <p>{@code nbf} is mandatory: without it Consul rejects the login with a validation error that says nothing about
 * the caller.
 */
public class StaticJwtM2MManager implements M2MManager {

    static final String PRIVATE_KEY = "CONSUL_LOGIN_M2M_PRIVATE_KEY";
    private static final String ISSUER = "CONSUL_LOGIN_M2M_ISSUER";
    private static final String AUDIENCE = "CONSUL_LOGIN_M2M_AUDIENCE";
    private static final String SUBJECT = "CONSUL_LOGIN_M2M_SUBJECT";

    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    @Override
    public Token getToken() {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(LIFETIME);
        return new Token("Bearer", jwt(issuedAt, expiresAt), issuedAt, expiresAt);
    }

    private String jwt(Instant issuedAt, Instant expiresAt) {
        String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = encode("{"
                + "\"iss\":\"" + required(ISSUER) + "\","
                + "\"sub\":\"" + required(SUBJECT) + "\","
                + "\"aud\":\"" + required(AUDIENCE) + "\","
                + "\"iat\":" + issuedAt.getEpochSecond() + ","
                + "\"nbf\":" + issuedAt.minus(CLOCK_SKEW).getEpochSecond() + ","
                + "\"exp\":" + expiresAt.getEpochSecond()
                + "}");
        String signingInput = header + "." + payload;
        return signingInput + "." + BASE64_URL.encodeToString(sign(signingInput));
    }

    private byte[] sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign the m2m token", e);
        }
    }

    private PrivateKey privateKey() {
        try {
            byte[] encoded = Base64.getDecoder().decode(required(PRIVATE_KEY));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the m2m signing key from " + PRIVATE_KEY, e);
        }
    }

    private static String encode(String json) {
        return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is required by the m2m stand-in");
        }
        return value;
    }
}
