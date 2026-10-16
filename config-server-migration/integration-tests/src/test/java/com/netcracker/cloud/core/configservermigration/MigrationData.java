package com.netcracker.cloud.core.configservermigration;

import java.util.List;
import java.util.Map;

/**
 * The properties the before-migration phase writes through config-server, and the requests whose answers both phases
 * compare. The data stays clear of the cases where core-legacy-api is known to answer differently: conflicting keys and
 * array indexes above 10000, which the migration guide lists, and nested or escaped placeholders.
 */
final class MigrationData {

    static final String APPLICATION = "migration-it";

    /** A default config-server writes on its first start, which the before-migration phase changes. */
    static final String CHANGED_DEFAULT = "error.page.customerSpecifier";
    static final String CHANGED_DEFAULT_VALUE = "migration-it";
    static final String CHANGED_DEFAULT_KV_KEY = "config/core/application/error/page/customerSpecifier";

    static final Map<String, Object> GLOBAL_DEFAULT = Map.of(
            "migration-test.first", "one",
            "migration-test.second", "two",
            CHANGED_DEFAULT, CHANGED_DEFAULT_VALUE);

    static final Map<String, Object> APPLICATION_DEFAULT = Map.of(
            "db.pool.size", "10",
            "servers[0]", "alpha",
            "servers[1]", "beta",
            "server.host", "localhost",
            "app.url", "http://${server.host}:8080");

    /** Overrides {@code db.pool.size}; the number and the boolean go into the request body unquoted. */
    static final Map<String, Object> APPLICATION_DEV = Map.of(
            "db.pool.size", "20",
            "greeting", "Hello, world",
            "banner", "line one\nline two",
            "empty.value", "",
            "retry.count", 3,
            "feature.enabled", true);

    static final String DEV_GREETING_KV_KEY = "config/core/migration-it,dev/greeting";

    static final List<String> REQUESTS = List.of(
            "/applications",
            "/migration-it/default",
            "/migration-it/dev",
            "/migration-it-default.json?resolvePlaceholders=true",
            "/migration-it-default.json?resolvePlaceholders=false",
            "/migration-it-default.properties?resolvePlaceholders=true",
            "/migration-it-default.properties?resolvePlaceholders=false",
            "/migration-it-default.yaml?resolvePlaceholders=true",
            "/migration-it-default.yaml?resolvePlaceholders=false",
            "/migration-it-dev.json?resolvePlaceholders=true",
            "/migration-it-dev.json?resolvePlaceholders=false",
            "/migration-it-dev.properties?resolvePlaceholders=true",
            "/migration-it-dev.properties?resolvePlaceholders=false",
            "/migration-it-dev.yaml?resolvePlaceholders=true",
            "/migration-it-dev.yaml?resolvePlaceholders=false");

    private MigrationData() {
    }
}
