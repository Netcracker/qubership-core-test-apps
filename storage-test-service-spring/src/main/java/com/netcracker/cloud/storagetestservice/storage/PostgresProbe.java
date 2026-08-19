package com.netcracker.cloud.storagetestservice.storage;

import com.netcracker.cloud.dbaas.client.config.DbaasPostgresConfiguration;
import com.netcracker.cloud.storagetestservice.workload.HandleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL through the DBaaS client. The injected DataSource is the DBaaS proxy, whose cached
 * client is what the library rebuilds after a leader change.
 */
@Component
public class PostgresProbe implements StorageProbe {

    private static final Logger log = LoggerFactory.getLogger(PostgresProbe.class);

    private static final String TABLE = "storage_probe";

    /** Captured once at startup, the way a service that wires a DataSource at boot holds it. */
    private final DataSource dataSource;

    /** The handle held across operations in {@link HandleMode#LONG_HELD}. */
    private volatile Connection heldConnection;

    private volatile boolean initialised;

    public PostgresProbe(@Qualifier(DbaasPostgresConfiguration.SERVICE_POSTGRES_DATASOURCE) DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String type() {
        return "postgresql";
    }

    @Override
    public synchronized void init() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "create table if not exists " + TABLE + " (k text primary key, v text not null)")) {
            statement.execute();
            initialised = true;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialise the probe table", e);
        }
    }

    @Override
    public String writeAndRead(HandleMode handleMode, String key, String value) {
        return withConnection(handleMode, connection -> {
            try (PreparedStatement write = connection.prepareStatement(
                    "insert into " + TABLE + " (k, v) values (?, ?) on conflict (k) do update set v = excluded.v")) {
                write.setString(1, key);
                write.setString(2, value);
                write.executeUpdate();
            }
            return readWith(connection, key);
        });
    }

    @Override
    public String read(HandleMode handleMode, String key) {
        return withConnection(handleMode, connection -> readWith(connection, key));
    }

    @Override
    public synchronized void releaseHeldHandle() {
        closeQuietly(heldConnection);
        heldConnection = null;
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("initialised", initialised);
        diagnostics.put("holdsConnection", heldConnection != null);
        diagnostics.put("dataSourceClass", dataSource.getClass().getName());
        return diagnostics;
    }

    /**
     * Runs the operation per the handle mode. Under LONG_HELD a broken connection is replaced from
     * the DataSource captured at startup, never by asking the container for the bean again.
     */
    private <T> T withConnection(HandleMode handleMode, SqlCall<T> call) {
        if (handleMode == HandleMode.PER_CALL) {
            try (Connection connection = dataSource.getConnection()) {
                return call.apply(connection);
            } catch (SQLException e) {
                throw new StorageOperationException(e);
            }
        }
        try {
            return call.apply(heldConnection());
        } catch (SQLException e) {
            // the held connection did not survive; the next operation resolves a new one
            releaseHeldHandle();
            throw new StorageOperationException(e);
        }
    }

    private Connection heldConnection() throws SQLException {
        Connection current = heldConnection;
        if (current != null && !current.isClosed()) {
            return current;
        }
        synchronized (this) {
            if (heldConnection == null || heldConnection.isClosed()) {
                log.info("Resolving a new long-held connection from the DataSource captured at startup");
                heldConnection = dataSource.getConnection();
            }
            return heldConnection;
        }
    }

    private static String readWith(Connection connection, String key) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("select v from " + TABLE + " where k = ?")) {
            read.setString(1, key);
            try (ResultSet rs = read.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Failed to close the held connection", e);
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Unchecked wrapper, so the workload records one error shape regardless of storage. */
    public static class StorageOperationException extends RuntimeException {
        public StorageOperationException(Throwable cause) {
            super(cause);
        }
    }
}
