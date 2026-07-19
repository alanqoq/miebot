package com.mieai.qqbot.app.database;

import com.mieai.qqbot.admin.database.DatabasePassword;
import com.mieai.qqbot.admin.database.DatabaseSettings;
import com.mieai.qqbot.admin.database.DatabaseSslMode;
import com.mieai.qqbot.admin.database.DatabaseType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

final class DatabaseProfile implements AutoCloseable {
    private final DatabaseType type;
    private final Path sqlitePath;
    private final Duration busyTimeout;
    private final String host;
    private final int port;
    private final String databaseName;
    private final String username;
    private char[] password;
    private final DatabaseSslMode sslMode;
    private final Duration connectTimeout;

    private DatabaseProfile(
            DatabaseType type,
            Path sqlitePath,
            Duration busyTimeout,
            String host,
            int port,
            String databaseName,
            String username,
            char[] password,
            DatabaseSslMode sslMode,
            Duration connectTimeout) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.sqlitePath = sqlitePath;
        this.busyTimeout = busyTimeout;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password == null ? null : password.clone();
        this.sslMode = sslMode;
        this.connectTimeout = connectTimeout;
        validate();
    }

    static DatabaseProfile sqlite(Path path, Duration busyTimeout) {
        return new DatabaseProfile(
                DatabaseType.SQLITE,
                Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize(),
                Objects.requireNonNull(busyTimeout, "busyTimeout must not be null"),
                null,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    static DatabaseProfile server(
            DatabaseType type,
            String host,
            int port,
            String databaseName,
            String username,
            char[] password,
            DatabaseSslMode sslMode,
            Duration connectTimeout) {
        if (type == DatabaseType.SQLITE) {
            throw new IllegalArgumentException("server profile type must not be SQLITE");
        }
        return new DatabaseProfile(
                type,
                null,
                null,
                requireText(host, "host"),
                port,
                requireText(databaseName, "databaseName"),
                requireText(username, "username"),
                Objects.requireNonNull(password, "password must not be null"),
                Objects.requireNonNull(sslMode, "sslMode must not be null"),
                Objects.requireNonNull(connectTimeout, "connectTimeout must not be null"));
    }

    static DatabaseProfile fromSettings(DatabaseSettings settings, char[] resolvedPassword) {
        Objects.requireNonNull(settings, "settings must not be null");
        if (settings.type() == DatabaseType.SQLITE) {
            return sqlite(Path.of(settings.sqlitePath()), Duration.ofMillis(settings.busyTimeoutMs()));
        }
        return server(
                settings.type(),
                settings.host(),
                settings.port(),
                settings.databaseName(),
                settings.username(),
                resolvedPassword,
                settings.sslMode(),
                Duration.ofMillis(settings.connectTimeoutMs()));
    }

    DatabaseType type() {
        return type;
    }

    Path sqlitePath() {
        return sqlitePath;
    }

    Duration busyTimeout() {
        return busyTimeout;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String databaseName() {
        return databaseName;
    }

    String username() {
        return username;
    }

    DatabaseSslMode sslMode() {
        return sslMode;
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    synchronized char[] copyPassword() {
        if (type == DatabaseType.SQLITE) {
            return null;
        }
        if (password == null) {
            throw new IllegalStateException("database password has been destroyed");
        }
        return password.clone();
    }

    boolean hasPassword() {
        return type != DatabaseType.SQLITE;
    }

    boolean sameCredentialScope(DatabaseSettings settings) {
        return settings != null
                && type == settings.type()
                && type != DatabaseType.SQLITE
                && Objects.equals(host, settings.host())
                && port == settings.port()
                && Objects.equals(databaseName, settings.databaseName())
                && Objects.equals(username, settings.username());
    }

    DatabaseProfile copy() {
        char[] copiedPassword = copyPassword();
        try {
            return type == DatabaseType.SQLITE
                    ? sqlite(sqlitePath, busyTimeout)
                    : server(type, host, port, databaseName, username, copiedPassword, sslMode, connectTimeout);
        } finally {
            if (copiedPassword != null) {
                Arrays.fill(copiedPassword, '\0');
            }
        }
    }

    @Override
    public synchronized void close() {
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
    }

    @Override
    public String toString() {
        return "DatabaseProfile[type=" + type + ", password=<redacted>]";
    }

    private void validate() {
        if (type == DatabaseType.SQLITE) {
            if (sqlitePath == null || busyTimeout == null || busyTimeout.isNegative() || busyTimeout.isZero()) {
                throw new IllegalArgumentException("SQLite path and positive busy timeout are required");
            }
            return;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("database port must be between 1 and 65535");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connect timeout must be positive");
        }
        if (host.codePoints().anyMatch(Character::isWhitespace)
                || host.indexOf('/') >= 0
                || host.indexOf('?') >= 0
                || host.indexOf('#') >= 0) {
            throw new IllegalArgumentException("database host contains unsupported characters");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
