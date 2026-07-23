package com.mieai.qqbot.admin.database;

public record DatabaseSettings(
        DatabaseType type,
        String sqlitePath,
        Long busyTimeoutMs,
        String host,
        Integer port,
        String databaseName,
        String username,
        DatabasePassword password,
        DatabaseSslMode sslMode,
        Long connectTimeoutMs) {
}
