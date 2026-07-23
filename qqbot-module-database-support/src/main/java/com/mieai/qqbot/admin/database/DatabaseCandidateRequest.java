package com.mieai.qqbot.admin.database;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public record DatabaseCandidateRequest(
        @NotNull DatabaseType type,
        @Size(max = 4096) String sqlitePath,
        @Min(100) @Max(60000) Long busyTimeoutMs,
        @Size(max = 253) String host,
        @Min(1) @Max(65535) Integer port,
        @Size(max = 128) String databaseName,
        @Size(max = 128) String username,
        @Size(max = 4096) String password,
        DatabaseSslMode sslMode,
        @Min(500) @Max(60000) Long connectTimeoutMs) {

    DatabaseSettings toSettings(DatabasePassword protectedPassword) {
        Map<String, String> fields = validateFields();
        if (!fields.isEmpty()) {
            throw new DatabaseAdministrationException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Request validation failed",
                    fields);
        }
        return new DatabaseSettings(
                type,
                normalize(sqlitePath),
                busyTimeoutMs,
                normalize(host),
                port,
                normalize(databaseName),
                normalize(username),
                protectedPassword,
                sslMode,
                connectTimeoutMs);
    }

    private Map<String, String> validateFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        if (type == DatabaseType.SQLITE) {
            requireText(fields, "sqlitePath", sqlitePath, "SQLite path is required");
            requireValue(fields, "busyTimeoutMs", busyTimeoutMs, "SQLite busy timeout is required");
        } else if (type != null) {
            requireText(fields, "host", host, "Database host is required");
            requireValue(fields, "port", port, "Database port is required");
            requireText(fields, "databaseName", databaseName, "Database name is required");
            requireText(fields, "username", username, "Database username is required");
            requireValue(fields, "sslMode", sslMode, "SSL mode is required");
            requireValue(fields, "connectTimeoutMs", connectTimeoutMs, "Connection timeout is required");
        }
        return fields;
    }

    private static void requireText(
            Map<String, String> fields,
            String name,
            String value,
            String message) {
        if (value == null || value.isBlank()) {
            fields.put(name, message);
        } else if (!value.equals(value.strip())) {
            fields.put(name, "Value must not have surrounding whitespace");
        }
    }

    private static void requireValue(
            Map<String, String> fields,
            String name,
            Object value,
            String message) {
        if (value == null) {
            fields.put(name, message);
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip();
    }

    @Override
    public String toString() {
        return "DatabaseCandidateRequest[type=" + type + ", password=<redacted>]";
    }
}
