package com.mieai.qqbot.persistence.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteMigrationTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void migratesARealStrictSQLiteDatabaseIdempotently() throws SQLException {
        Path databaseFile = temporaryDirectory.resolve("database with space.db");
        DataSource dataSource = SQLiteDataSourceFactory.create(databaseFile);

        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isEqualTo(13);
        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isZero();

        assertThat(databaseFile).isRegularFile();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryInt(connection, """
                    SELECT count(*)
                    FROM pragma_table_list
                    WHERE schema = 'main'
                      AND name IN ('admin_users', 'admin_login_attempts', 'audit_logs', 'bot_leases', 'bots', 'event_inbox', 'outbox_jobs', 'plugin_artifacts', 'bot_plugins', 'plugin_deliveries', 'plugin_storage', 'instance_plugin_hashes')
                      AND strict = 1
                """)).isEqualTo(12);
            assertThat(queryString(connection,
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'bots'"))
                    .contains("UNIQUE (environment, app_id)")
                    .contains("app_secret_ciphertext")
                    .contains("app_secret_key_id")
                    .contains("max_media_upload_bytes")
                    .contains("revision");
        }
    }

    @Test
    void configuresWalForeignKeysAndBusyTimeoutOnConnections() throws SQLException {
        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("settings.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);

        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            assertThat(queryString(first, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(queryInt(first, "PRAGMA foreign_keys")).isEqualTo(1);
            assertThat(queryInt(first, "PRAGMA busy_timeout")).isEqualTo(5_000);
            assertThat(queryInt(second, "PRAGMA foreign_keys")).isEqualTo(1);
            assertThat(queryInt(second, "PRAGMA busy_timeout")).isEqualTo(5_000);
        }
    }

    @Test
    void appliesCustomBusyTimeoutAndValidatesFactoryArguments() throws SQLException {
        DataSource dataSource = SQLiteDataSourceFactory.create(
                temporaryDirectory.resolve("custom-timeout.db"), Duration.ofMillis(275));

        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryInt(connection, "PRAGMA busy_timeout")).isEqualTo(275);
        }

        assertThatNullPointerException().isThrownBy(() -> SQLiteDataSourceFactory.create(null));
        assertThatNullPointerException().isThrownBy(() -> SQLiteDataSourceFactory.create(
                temporaryDirectory.resolve("null-timeout.db"), null));
        assertThatIllegalArgumentException().isThrownBy(() -> SQLiteDataSourceFactory.create(
                temporaryDirectory.resolve("zero-timeout.db"), Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> SQLiteDataSourceFactory.create(
                temporaryDirectory.resolve("large-timeout.db"),
                Duration.ofMillis((long) Integer.MAX_VALUE + 1L)));
        assertThatNullPointerException().isThrownBy(() -> SQLiteDatabaseInitializer.migrate(null));
    }

    @Test
    void createsOnlyTheExpectedApplicationTablesThroughV013() throws SQLException {
        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("schema.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT name
                        FROM sqlite_master
                        WHERE type = 'table' AND name NOT LIKE 'flyway_%' AND name NOT LIKE 'sqlite_%'
                        ORDER BY name
                        """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("SPRING_SESSION");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("SPRING_SESSION_ATTRIBUTES");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("admin_login_attempts");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("admin_users");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("audit_logs");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("bot_leases");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("bot_plugins");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("bots");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("event_inbox");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("instance_plugin_hashes");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("outbox_jobs");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("plugin_artifacts");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("plugin_deliveries");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("plugin_storage");
            assertThat(resultSet.next()).isFalse();
        }
        assertThat(Files.exists(temporaryDirectory.resolve("schema.db"))).isTrue();
    }

    @Test
    void upgradesLegacyZeroIntentsToTheCurrentGroupAndC2cDefault() throws SQLException {
        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("legacy.db"));
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/sqlite")
                .target("3")
                .load()
                .migrate();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bots (
                        id, display_name, app_id, environment,
                        app_secret_ciphertext, app_secret_key_id,
                        intents, shard_index, shard_count, enabled, revision,
                        created_at, updated_at
                    ) VALUES (
                        '550e8400-e29b-41d4-a716-446655440001', 'Legacy Bot', '1905208810',
                        'PRODUCTION', 'ciphertext', 'primary', 0, 0, 1, 1, 1,
                        '2026-07-17T12:00:00Z', '2026-07-17T12:00:00Z'
                    )
                    """);
        }

        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isEqualTo(10);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryInt(connection, "SELECT intents FROM bots"))
                    .isEqualTo(33_554_432);
            assertThat(queryInt(connection, "SELECT revision FROM bots")).isEqualTo(2);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
