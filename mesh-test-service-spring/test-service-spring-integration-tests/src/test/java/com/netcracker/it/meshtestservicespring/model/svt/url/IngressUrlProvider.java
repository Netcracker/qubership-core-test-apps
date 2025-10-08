package com.netcracker.it.meshtestservicespring.model.svt.url;

import com.netcracker.it.meshtestservicespring.utils.CloseableUrl;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.function.Function;

import static com.netcracker.it.meshtestservicespring.Const.*;

public class IngressUrlProvider implements Function<String, CloseableUrl> {

    @Override
    public CloseableUrl apply(String ingressName) {
        final String urlString = String.format("%s%s-%s.%s/", PROTOCOL_PREFIX, ingressName, ORIGIN_NAMESPACE, ENV_CLOUD_PUBLIC_HOST);
        try {
            return new IngressUrl(new URL(urlString));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @RequiredArgsConstructor
    private static class IngressUrl implements CloseableUrl {
        @Getter
        private final URL url;

        @Override
        public void close() {
            // no-op
        }
    }
}
