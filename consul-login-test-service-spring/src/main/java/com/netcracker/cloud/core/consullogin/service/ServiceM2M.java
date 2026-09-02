package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.DummyM2MManager;
import com.netcracker.cloud.security.core.auth.M2MManager;

/**
 * Chooses which stand-in for the customer security library the service uses.
 *
 * <p>{@link DummyM2MManager} is the default: its token is rejected by Consul, so the m2m way reaches the login and
 * fails, which is what most scenarios want to observe. With a signing key in {@code CONSUL_LOGIN_M2M_PRIVATE_KEY}
 * the m2m way logs in with a JWT of its own instead, which is the old way end to end.
 */
final class ServiceM2M {

    private ServiceM2M() {
    }

    static M2MManager standIn() {
        return signingKeyProvided() ? new StaticJwtM2MManager() : new DummyM2MManager();
    }

    private static boolean signingKeyProvided() {
        String key = System.getenv(StaticJwtM2MManager.PRIVATE_KEY);
        return key != null && !key.isBlank();
    }
}
