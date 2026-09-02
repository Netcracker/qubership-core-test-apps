package com.netcracker.cloud.core.consullogin;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The key pair the m2m stand-in signs with. Generated per run, so no private key is kept in the repository: Consul
 * gets the public half in the auth method, the pod gets the private half in its environment.
 */
final class SigningKey {

    static final String ISSUER = "https://consul-login-integration-tests";
    static final String AUDIENCE = "consul";

    private final KeyPair pair;

    private SigningKey(KeyPair pair) {
        this.pair = pair;
    }

    static SigningKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new SigningKey(generator.generateKeyPair());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is required by the platform", e);
        }
    }

    String publicKeyPem() {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
    }

    String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}
