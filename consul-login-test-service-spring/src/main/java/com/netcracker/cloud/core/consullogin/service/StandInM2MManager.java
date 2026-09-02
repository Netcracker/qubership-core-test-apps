package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.DummyM2MManager;
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
 * Stands in for the customer security library that real services pull in. Spring reaches it twice, through the
 * bootstrap registry of the ConfigData phase and through a bean of the application context, and both hand out an
 * instance of this class.
 *
 * <p>With a signing key in {@code CONSUL_LOGIN_M2M_PRIVATE_KEY} it signs its own JWT, which is the old way end to end
 * on a stand that has no Identity Provider; Consul is configured with the matching public key. Without a key it hands
 * out the dummy token, which Consul rejects — that is what the scenarios of the new way want to observe.
 *
 * <p>{@code nbf} is mandatory: without it Consul rejects the login with a validation error that says nothing about
 * the caller.
 */
public class StandInM2MManager implements M2MManager {

    static final String PRIVATE_KEY = "CONSUL_LOGIN_M2M_PRIVATE_KEY";
    private static final String ISSUER = "CONSUL_LOGIN_M2M_ISSUER";
    private static final String AUDIENCE = "CONSUL_LOGIN_M2M_AUDIENCE";
    private static final String SUBJECT = "CONSUL_LOGIN_M2M_SUBJECT";

    private static final Duration LIFETIME = Duration.ofMinutes(10);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final M2MManager fallback = new DummyM2MManager();

    @Override
    public Token getToken() {
        if (!signingKeyProvided()) {
            return fallback.getToken();
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(LIFETIME);
        return new Token("Bearer", jwt(issuedAt, expiresAt), issuedAt, expiresAt);
    }

    private static boolean signingKeyProvided() {
        String key = System.getenv(PRIVATE_KEY);
        return key != null && !key.isBlank();
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
