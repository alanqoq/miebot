package com.mieai.qqbot.app.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.mieai.qqbot.admin.database.DatabaseSslMode;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

final class DatabaseConfigurationStore {
    private static final String PASSWORD_PURPOSE = "database-connection-password";

    private final Path activeFile;
    private final Path candidateFile;
    private final ObjectMapper objectMapper;
    private final ObjectWriter writer;
    private final AesGcmConfigurationSecretCipher secretCipher;

    DatabaseConfigurationStore(
            Path file,
            ObjectMapper objectMapper,
            AesGcmConfigurationSecretCipher secretCipher) {
        this(file, defaultCandidateFile(file), objectMapper, secretCipher);
    }

    DatabaseConfigurationStore(
            Path activeFile,
            Path candidateFile,
            ObjectMapper objectMapper,
            AesGcmConfigurationSecretCipher secretCipher) {
        this.activeFile = normalize(activeFile, "activeFile");
        this.candidateFile = normalize(candidateFile, "candidateFile");
        if (this.activeFile.equals(this.candidateFile)) {
            throw new IllegalArgumentException("active and candidate database configuration files must differ");
        }
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.writer = objectMapper.writerWithDefaultPrettyPrinter();
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
    }

    Path file() {
        return activeFile;
    }

    Path candidateFile() {
        return candidateFile;
    }

    Optional<LoadedConfiguration> loadPersisted() {
        return load(activeFile, "active");
    }

    LoadedConfiguration loadCandidateRequired() {
        return load(candidateFile, "candidate").orElseThrow(() ->
                new IllegalStateException("Database candidate configuration file does not exist: " + candidateFile));
    }

    private Optional<LoadedConfiguration> load(Path source, String description) {
        if (!Files.exists(source)) {
            return Optional.empty();
        }
        try {
            StoredConfiguration stored = objectMapper.readValue(source.toFile(), StoredConfiguration.class);
            return Optional.of(toLoaded(stored, source));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to read " + description + " database configuration file", exception);
        }
    }

    LoadedConfiguration loadRequired() {
        return loadPersisted().orElseThrow(() ->
                new IllegalStateException("Database configuration file does not exist: " + activeFile));
    }

    PreparedConfiguration prepare(DatabaseProfile profile, long revision, Instant lastSwitchedAt) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(lastSwitchedAt, "lastSwitchedAt must not be null");

        EncryptedConfigurationValue encrypted = null;
        char[] password = profile.copyPassword();
        try {
            if (password != null) {
                encrypted = secretCipher.encrypt(password, PASSWORD_PURPOSE);
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }

        StoredConfiguration stored = StoredConfiguration.from(profile, revision, lastSwitchedAt, encrypted);
        Path parent = activeFile.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, activeFile.getFileName().toString(), ".pending");
            writer.writeValue(temporary.toFile(), stored);
            return new PreparedConfiguration(temporary);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare database configuration file", exception);
        }
    }

    void commit(PreparedConfiguration prepared) {
        Objects.requireNonNull(prepared, "prepared must not be null");
        prepared.commitTo(activeFile);
    }

    DatabaseProfile fromBootstrap(DatabaseBootstrapProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        DatabaseType type = Objects.requireNonNull(properties.getType(), "database type must not be null");
        if (type == DatabaseType.SQLITE) {
            DatabaseBootstrapProperties.Sqlite sqlite = properties.getSqlite();
            return DatabaseProfile.sqlite(sqlite.getPath(), sqlite.getBusyTimeout());
        }
        DatabaseBootstrapProperties.Server server = type == DatabaseType.MYSQL
                ? properties.getMysql()
                : properties.getPostgresql();
        char[] password = readBootstrapPassword(server);
        try {
            return DatabaseProfile.server(
                    type,
                    server.getHost(),
                    server.getPort(),
                    server.getDatabaseName(),
                    server.getUsername(),
                    password,
                    server.getSslMode(),
                    server.getConnectTimeout());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private LoadedConfiguration toLoaded(StoredConfiguration stored, Path source) {
        Objects.requireNonNull(stored, "stored configuration must not be null");
        Objects.requireNonNull(source, "source must not be null");
        long revision = stored.revision() == null ? 1L : stored.revision();
        if (revision < 1L) {
            throw new IllegalArgumentException("database configuration revision must be positive");
        }
        DatabaseType type = Objects.requireNonNull(stored.type(), "database configuration type is required");
        Instant switchedAt = stored.lastSwitchedAt();
        if (type == DatabaseType.SQLITE) {
            if (stored.sqlitePath() == null || stored.busyTimeoutMs() == null) {
                throw new IllegalArgumentException("SQLite configuration is incomplete");
            }
            return new LoadedConfiguration(
                    DatabaseProfile.sqlite(
                            resolveRelativePath(stored.sqlitePath(), source),
                            Duration.ofMillis(stored.busyTimeoutMs())),
                    revision,
                    switchedAt);
        }

        char[] password = readStoredPassword(stored, source);
        try {
            DatabaseProfile profile = DatabaseProfile.server(
                    type,
                    stored.host(),
                    requireValue(stored.port(), "port"),
                    stored.databaseName(),
                    stored.username(),
                    password,
                    Objects.requireNonNull(stored.sslMode(), "sslMode is required"),
                    Duration.ofMillis(requireValue(stored.connectTimeoutMs(), "connectTimeoutMs")));
            return new LoadedConfiguration(profile, revision, switchedAt);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private char[] readStoredPassword(StoredConfiguration stored, Path source) {
        boolean encrypted = stored.passwordCiphertext() != null || stored.passwordKeyId() != null;
        int sources = (encrypted ? 1 : 0)
                + (stored.password() == null ? 0 : 1)
                + (stored.passwordFile() == null ? 0 : 1);
        if (sources != 1) {
            throw new IllegalArgumentException("Configure exactly one database password source");
        }
        if (encrypted) {
            if (stored.passwordCiphertext() == null || stored.passwordKeyId() == null) {
                throw new IllegalArgumentException("Encrypted database password is incomplete");
            }
            return secretCipher.decrypt(
                    new EncryptedConfigurationValue(stored.passwordCiphertext(), stored.passwordKeyId()),
                    PASSWORD_PURPOSE);
        }
        if (stored.passwordFile() != null) {
            return readPasswordFile(resolveRelativePath(stored.passwordFile(), source));
        }
        return stored.password().toCharArray();
    }

    private char[] readBootstrapPassword(DatabaseBootstrapProperties.Server server) {
        String configured = server.getPassword();
        Path configuredFile = server.getPasswordFile();
        if (configuredFile != null && configuredFile.toString().isBlank()) {
            configuredFile = null;
        }
        boolean hasValue = configured != null && !configured.isEmpty();
        if (hasValue && configuredFile != null) {
            throw new IllegalStateException("Configure only one database password or password file");
        }
        if (configuredFile != null) {
            return readPasswordFile(configuredFile);
        }
        return configured == null ? new char[0] : configured.toCharArray();
    }

    private static Path resolveRelativePath(String configured, Path configurationFile) {
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }
        Path parent = configurationFile.getParent();
        return (parent == null ? path : parent.resolve(path)).normalize();
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name + " must not be null").toAbsolutePath().normalize();
    }

    private static Path defaultCandidateFile(Path configuredActiveFile) {
        Path active = normalize(configuredActiveFile, "file");
        return active.resolveSibling(active.getFileName() + ".candidate");
    }

    private static char[] readPasswordFile(Path path) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            return value.toCharArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read database password file", exception);
        }
    }

    private static long requireValue(Long value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int requireValue(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    record LoadedConfiguration(DatabaseProfile profile, long revision, Instant lastSwitchedAt) {
        LoadedConfiguration {
            Objects.requireNonNull(profile, "profile must not be null");
        }
    }

    static final class PreparedConfiguration implements AutoCloseable {
        private Path temporary;

        private PreparedConfiguration(Path temporary) {
            this.temporary = temporary;
        }

        private synchronized void commitTo(Path target) {
            if (temporary == null) {
                throw new IllegalStateException("prepared configuration has already been consumed");
            }
            try {
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to commit database configuration file", exception);
            }
        }

        @Override
        public synchronized void close() {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an uncommitted candidate file.
                }
                temporary = null;
            }
        }
    }

    private record StoredConfiguration(
            Long revision,
            DatabaseType type,
            String sqlitePath,
            Long busyTimeoutMs,
            String host,
            Integer port,
            String databaseName,
            String username,
            DatabaseSslMode sslMode,
            Long connectTimeoutMs,
            String passwordCiphertext,
            String passwordKeyId,
            String password,
            String passwordFile,
            Instant lastSwitchedAt) {

        private static StoredConfiguration from(
                DatabaseProfile profile,
                long revision,
                Instant lastSwitchedAt,
                EncryptedConfigurationValue encrypted) {
            return new StoredConfiguration(
                    revision,
                    profile.type(),
                    profile.sqlitePath() == null ? null : profile.sqlitePath().toString(),
                    profile.busyTimeout() == null ? null : profile.busyTimeout().toMillis(),
                    profile.host(),
                    profile.type() == DatabaseType.SQLITE ? null : profile.port(),
                    profile.databaseName(),
                    profile.username(),
                    profile.sslMode(),
                    profile.connectTimeout() == null ? null : profile.connectTimeout().toMillis(),
                    encrypted == null ? null : encrypted.ciphertext(),
                    encrypted == null ? null : encrypted.keyId(),
                    null,
                    null,
                    lastSwitchedAt);
        }
    }
}
