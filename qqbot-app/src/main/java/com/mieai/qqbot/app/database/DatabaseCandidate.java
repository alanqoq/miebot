package com.mieai.qqbot.app.database;

import com.mieai.qqbot.admin.database.DatabaseSchemaState;
import java.util.Objects;
import javax.sql.DataSource;

final class DatabaseCandidate implements AutoCloseable {
    private final DataSource dataSource;
    private final String product;
    private final String version;
    private final long latencyMs;
    private final DatabaseSchemaState schemaState;
    private final String schemaVersion;
    private final boolean initialized;
    private final long botCount;
    private boolean transferred;

    DatabaseCandidate(
            DataSource dataSource,
            String product,
            String version,
            long latencyMs,
            DatabaseSchemaState schemaState,
            String schemaVersion,
            boolean initialized,
            long botCount) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.product = Objects.requireNonNull(product, "product must not be null");
        this.version = Objects.requireNonNull(version, "version must not be null");
        this.latencyMs = latencyMs;
        this.schemaState = Objects.requireNonNull(schemaState, "schemaState must not be null");
        this.schemaVersion = schemaVersion;
        this.initialized = initialized;
        this.botCount = botCount;
    }

    DataSource transferDataSource() {
        if (transferred) {
            throw new IllegalStateException("candidate datasource has already been transferred");
        }
        transferred = true;
        return dataSource;
    }

    DataSource dataSource() {
        return dataSource;
    }

    String product() {
        return product;
    }

    String version() {
        return version;
    }

    long latencyMs() {
        return latencyMs;
    }

    DatabaseSchemaState schemaState() {
        return schemaState;
    }

    String schemaVersion() {
        return schemaVersion;
    }

    boolean initialized() {
        return initialized;
    }

    long botCount() {
        return botCount;
    }

    @Override
    public void close() {
        if (!transferred) {
            SwitchableDataSource.closeDataSource(dataSource);
        }
    }
}
