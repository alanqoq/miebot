package com.mieai.qqbot.admin.database;

import java.time.Instant;

public record DatabaseConfigurationView(
        long revision,
        DatabaseType type,
        String sqlitePath,
        Long busyTimeoutMs,
        String host,
        Integer port,
        String databaseName,
        String username,
        DatabaseSslMode sslMode,
        Long connectTimeoutMs,
        boolean passwordConfigured,
        String databaseProduct,
        String databaseVersion,
        boolean switchInProgress,
        Instant lastSwitchedAt) {
}
