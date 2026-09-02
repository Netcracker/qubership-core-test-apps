package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.M2MManager;
import org.springframework.boot.bootstrap.BootstrapRegistry;
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer;

/**
 * Puts {@link StandInM2MManager} into the bootstrap registry, where the security library of a real service would put
 * its own {@link M2MManager}. The ConfigData phase runs before the application context, so the bean published for the
 * other entry points is not visible there; without this registration the m2m way is unreachable in that phase and the
 * service measures its own gap instead of the library.
 *
 * <p>{@link ServiceM2MConfiguration} covers the other half — the same stand-in for the application context — and the
 * two cannot replace each other: the phases have separate registries.
 */
public class M2MManagerBootstrapper implements BootstrapRegistryInitializer {

    @Override
    public void initialize(BootstrapRegistry registry) {
        registry.registerIfAbsent(M2MManager.class, context -> new StandInM2MManager());
    }
}
