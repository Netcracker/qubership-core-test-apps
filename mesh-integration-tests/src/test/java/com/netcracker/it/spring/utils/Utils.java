package com.netcracker.it.spring.utils;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class Utils {

    public static final Gson GSON = new Gson();

    public static void runWithRetry(final Runnable runnable, final long timeoutMillis) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        Exception latestException = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                runnable.run();
                return;
            } catch (Exception e) {
                latestException = e;
                log.debug("Got exception running retryable operation: ", e);
                Thread.sleep(200);
            }
        }
        if (latestException != null) {
            log.error("All retries failed. Latest exception: ", latestException);
            throw latestException;
        }
    }

    public static <K, V> Map<K, V> newMap(KeyValuePair<K, V> ...entries) {
        final Map<K, V> map = new HashMap<>(entries.length);
        for (KeyValuePair<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static String replaceOrEmpty(String value, String regex, String replacement) {
        if (value == null) {
            return "";
        }

        return value.replaceAll(regex, replacement);
    }
}
