package com.netcracker.cloud.core.consullogin.service;

import com.netcracker.cloud.security.core.auth.M2MManager;
import com.netcracker.cloud.security.core.auth.Token;
import com.netcracker.cloud.security.core.utils.k8s.AudienceName;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesAudienceToken;

import java.time.Instant;

/**
 * Makes the m2m way succeed on a stand that has no Identity Provider, so that the fallback and the recheck of the
 * kubernetes way can be observed end to end.
 *
 * <p>It hands out the projected token of the pod, the same value the kubernetes way reads. Consul accepts it because
 * the stand registers an auth method of type kubernetes under the name of the namespace, which is the name the m2m
 * way logs in to. The library sees no difference: below the entry point a way is a pair of an auth method name and a
 * bearer token.
 */
public class ProjectedTokenM2MManager implements M2MManager {

    @Override
    public Token getToken() {
        String value = KubernetesAudienceToken.getToken(AudienceName.NETCRACKER);
        Instant now = Instant.now();
        return new Token("Bearer", value, now, now.plusSeconds(600));
    }
}
