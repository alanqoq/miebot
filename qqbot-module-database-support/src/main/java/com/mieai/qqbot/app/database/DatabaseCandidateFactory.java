package com.mieai.qqbot.app.database;

import com.mieai.qqbot.admin.database.DatabaseSchemaState;
import com.mieai.qqbot.admin.database.DatabaseSslMode;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

final class DatabaseCandidateFactory {
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "bots", "event_inbox", "outbox_jobs", "admin_users",
            "plugin_artifacts", "bot_plugins", "plugin_deliveries");
    private final ModuleDatabaseMigrator moduleMigrator;

    DatabaseCandidateFactory() {
        moduleMigrator = null;
    }

    DatabaseCandidateFactory(ModuleDatabaseMigrator moduleMigrator) {
        this.moduleMigrator = Objects.requireNonNull(
                moduleMigrator, "moduleMigrator must not be null");
    }

    DatabaseCandidate prepare(DatabaseProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        long started = System.nanoTime();
        DataSource dataSource = createDataSource(profile);
        try {
            Metadata metadata = metadata(dataSource);
            DatabaseSchemaState initialState = inspectSchema(dataSource);
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/" + migrationDirectory(profile.type()))
                    .cleanDisabled(true)
                    .validateMigrationNaming(true)
                    .load();
            MigrateResult migration = flyway.migrate();
            int moduleMigrations = moduleMigrator == null ? 0 : moduleMigrator.migrate(
                    dataSource, migrationDirectory(profile.type()));
            verifyRequiredTables(dataSource);
            verifyWriteAndRead(dataSource);
            long botCount = count(dataSource, "bots");
            MigrationInfo current = flyway.info().current();
            return new DatabaseCandidate(
                    dataSource,
                    metadata.product(),
                    metadata.version(),
                    elapsedMillis(started),
                    initialState,
                    current == null || current.getVersion() == null ? null : current.getVersion().toString(),
                    migration.migrationsExecuted > 0 || moduleMigrations > 0,
                    botCount);
        } catch (RuntimeException exception) {
            SwitchableDataSource.closeDataSource(dataSource);
            throw exception;
        }
    }

    boolean isUnwritten(DataSource dataSource) {
        return count(dataSource, "bots") == 0L
                && count(dataSource, "event_inbox") == 0L
                && count(dataSource, "outbox_jobs") == 0L
                && count(dataSource, "plugin_artifacts") == 0L
                && count(dataSource, "bot_plugins") == 0L
                && count(dataSource, "plugin_deliveries") == 0L
                && count(dataSource, "admin_users") == 0L;
    }

    private DataSource createDataSource(DatabaseProfile profile) {
        if (profile.type() == DatabaseType.SQLITE) {
            return SQLiteDataSourceFactory.create(profile.sqlitePath(), profile.busyTimeout());
        }

        char[] password = profile.copyPassword();
        try {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl(jdbcUrl(profile));
            configuration.setUsername(profile.username());
            configuration.setPassword(new String(password));
            configuration.setConnectionTimeout(profile.connectTimeout().toMillis());
            configuration.setValidationTimeout(Math.min(profile.connectTimeout().toMillis(), 5_000L));
            configuration.setInitializationFailTimeout(profile.connectTimeout().toMillis());
            configuration.setMaximumPoolSize(8);
            configuration.setMinimumIdle(0);
            configuration.setAutoCommit(true);
            configuration.setPoolName("qqbot-" + profile.type().name().toLowerCase(Locale.ROOT)
                    + "-" + Integer.toUnsignedString(System.identityHashCode(profile)));
            return new HikariDataSource(configuration);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String jdbcUrl(DatabaseProfile profile) {
        String host = profile.host().contains(":") && !profile.host().startsWith("[")
                ? "[" + profile.host() + "]"
                : profile.host();
        String database = encodePathSegment(profile.databaseName());
        if (profile.type() == DatabaseType.MYSQL) {
            return "jdbc:mysql://" + host + ":" + profile.port() + "/" + database
                    + "?connectTimeout=" + profile.connectTimeout().toMillis()
                    + "&socketTimeout=" + Math.max(profile.connectTimeout().toMillis(), 10_000L)
                    + "&sslMode=" + profile.sslMode().name()
                    + "&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
        }
        long timeoutSeconds = Math.max(1L, profile.connectTimeout().toSeconds());
        return "jdbc:postgresql://" + host + ":" + profile.port() + "/" + database
                + "?connectTimeout=" + timeoutSeconds
                + "&socketTimeout=" + Math.max(timeoutSeconds, 10L)
                + "&sslmode=" + postgresSslMode(profile.sslMode())
                + "&ApplicationName=qqbot-platform";
    }

    private static String postgresSslMode(DatabaseSslMode mode) {
        return switch (mode) {
            case DISABLED -> "disable";
            case PREFERRED -> "prefer";
            case REQUIRED -> "require";
            case VERIFY_CA -> "verify-ca";
            case VERIFY_IDENTITY -> "verify-full";
        };
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Metadata metadata(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new Metadata(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());
        } catch (SQLException exception) {
            throw new DatabaseConnectionException(exception);
        }
    }

    private static DatabaseSchemaState inspectSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> tables = userTables(connection);
            if (tables.isEmpty()) {
                return DatabaseSchemaState.EMPTY;
            }
            if (tables.containsAll(REQUIRED_TABLES)) {
                return DatabaseSchemaState.READY;
            }
            if (tables.contains("flyway_schema_history") || tables.contains("bots")) {
                return DatabaseSchemaState.INITIALIZED;
            }
            throw new DatabaseSchemaException("Target database contains an unmanaged schema");
        } catch (SQLException exception) {
            throw new DatabaseReadException(exception);
        }
    }

    private static Set<String> userTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema;
        try {
            schema = connection.getSchema();
        } catch (SQLException | AbstractMethodError ignored) {
            schema = null;
        }
        try (ResultSet result = metadata.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
            while (result.next()) {
                String tableSchema = result.getString("TABLE_SCHEM");
                String name = result.getString("TABLE_NAME");
                if (name == null || isSystemTable(tableSchema, name)) {
                    continue;
                }
                tables.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private static boolean isSystemTable(String schema, String table) {
        String normalizedSchema = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        String normalizedTable = table.toLowerCase(Locale.ROOT);
        return normalizedSchema.equals("information_schema")
                || normalizedSchema.equals("pg_catalog")
                || normalizedSchema.equals("mysql")
                || normalizedSchema.equals("performance_schema")
                || normalizedSchema.equals("sys")
                || normalizedTable.startsWith("sqlite_");
    }

    private static void verifyRequiredTables(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> tables = userTables(connection);
            if (!tables.containsAll(REQUIRED_TABLES)) {
                Set<String> missing = new HashSet<>(REQUIRED_TABLES);
                missing.removeAll(tables);
                throw new DatabaseSchemaException("Target schema is missing tables: " + missing);
            }
        } catch (SQLException exception) {
            throw new DatabaseReadException(exception);
        }
    }

    private static void verifyWriteAndRead(DataSource dataSource) {
        String id = UUID.randomUUID().toString();
        String appId = "database-probe-" + UUID.randomUUID();
        String now = Instant.now().toString();
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO bots (
                            id, display_name, app_id, environment,
                            app_secret_ciphertext, app_secret_key_id,
                            intents, shard_index, shard_count, enabled, revision,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 'SANDBOX', ?, ?, 0, 0, 1, 0, 1, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, "Database verification probe");
                    insert.setString(3, appId);
                    insert.setString(4, "probe-ciphertext");
                    insert.setString(5, "probe-key");
                    insert.setString(6, now);
                    insert.setString(7, now);
                    if (insert.executeUpdate() != 1) {
                        throw new DatabaseWriteException("Database probe insert did not affect one row");
                    }
                }
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT display_name FROM bots WHERE id = ?")) {
                    query.setString(1, id);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next() || !"Database verification probe".equals(result.getString(1))) {
                            throw new DatabaseReadException("Database probe could not be read back");
                        }
                    }
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (DatabaseReadException | DatabaseWriteException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new DatabaseWriteException(exception);
        }
    }

    private static long count(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) {
                throw new DatabaseReadException("Count query returned no row for " + table);
            }
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new DatabaseReadException(exception);
        }
    }

    private static String migrationDirectory(DatabaseType type) {
        return switch (type) {
            case SQLITE -> "sqlite";
            case MYSQL -> "mysql";
            case POSTGRESQL -> "postgresql";
        };
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private record Metadata(String product, String version) {}

    static final class DatabaseConnectionException extends RuntimeException {
        DatabaseConnectionException(Throwable cause) {
            super("Unable to connect to target database", cause);
        }
    }

    static final class DatabaseReadException extends RuntimeException {
        DatabaseReadException(String message) {
            super(message);
        }

        DatabaseReadException(Throwable cause) {
            super("Unable to read from target database", cause);
        }
    }

    static final class DatabaseWriteException extends RuntimeException {
        DatabaseWriteException(String message) {
            super(message);
        }

        DatabaseWriteException(Throwable cause) {
            super("Unable to write to target database", cause);
        }
    }

    static final class DatabaseSchemaException extends RuntimeException {
        DatabaseSchemaException(String message) {
            super(message);
        }
    }
}
