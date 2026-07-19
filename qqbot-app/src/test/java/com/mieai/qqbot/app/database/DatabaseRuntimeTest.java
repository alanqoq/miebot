package com.mieai.qqbot.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mieai.qqbot.admin.database.DatabaseAdministrationException;
import com.mieai.qqbot.admin.database.DatabaseSettings;
import com.mieai.qqbot.admin.database.DatabaseSwitchResult;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.persistence.admin.AdminUser;
import com.mieai.qqbot.persistence.admin.JdbcAdminUserRepository;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.StaticKeyProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseRuntimeTest {
    private static final Instant ADMIN_CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    private Path temporaryDirectory;

    private Path initialDatabase;
    private Path activeConfiguration;
    private Path candidateConfiguration;
    private DatabaseCandidateFactory candidateFactory;
    private DatabaseConfigurationStore configurationStore;
    private DatabaseRuntime runtime;
    private AdminUser administrator;
    private AtomicInteger beforeActiveDatabaseChanges;
    private AtomicInteger activeDatabaseChanges;

    @BeforeEach
    void startRuntime() {
        initialDatabase = temporaryDirectory.resolve("initial.db");
        activeConfiguration = temporaryDirectory.resolve("database.json");
        candidateConfiguration = temporaryDirectory.resolve("database-candidate.json");
        candidateFactory = new DatabaseCandidateFactory();
        configurationStore = configurationStore();
        beforeActiveDatabaseChanges = new AtomicInteger();
        activeDatabaseChanges = new AtomicInteger();
        runtime = new DatabaseRuntime(
                bootstrap(initialDatabase),
                candidateFactory,
                configurationStore,
                java.time.Clock.systemUTC(),
                beforeActiveDatabaseChanges::incrementAndGet,
                activeDatabaseChanges::incrementAndGet);
        administrator = new AdminUser(
                UUID.fromString("17f37609-8b25-4ee8-92e9-d966d9b7754b"),
                "rootadmin",
                "$2a$12$database-runtime-test-password-hash",
                true,
                ADMIN_CREATED_AT,
                ADMIN_CREATED_AT);
        assertThat(new JdbcAdminUserRepository(runtime.dataSource()).insertFirst(administrator)).isTrue();
    }

    @AfterEach
    void closeRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void initializesEmptySQLiteTargetAndCopiesOnlyAdministrator() {
        Path target = temporaryDirectory.resolve("target.db");

        DatabaseSwitchResult result = runtime.switchDatabase(1L, sqliteSettings(target), administrator.username());

        assertThat(result.configuration().revision()).isEqualTo(2L);
        assertThat(result.verification().adminSeeded()).isTrue();
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(count(target, "admin_users")).isEqualTo(1L);
        assertThat(count(target, "bots")).isZero();
        assertThat(count(target, "event_inbox")).isZero();
        assertThat(count(target, "outbox_jobs")).isZero();
        assertThat(beforeActiveDatabaseChanges).hasValue(1);
        assertThat(activeDatabaseChanges).hasValue(1);

        AdminUser copied = new JdbcAdminUserRepository(SQLiteDataSourceFactory.create(target))
                .findByUsername(administrator.username())
                .orElseThrow();
        assertThat(copied.id()).isEqualTo(administrator.id());
        assertThat(copied.passwordHash()).isEqualTo(administrator.passwordHash());
        assertThat(copied.enabled()).isEqualTo(administrator.enabled());

        DatabaseConfigurationStore.LoadedConfiguration persisted = configurationStore.loadRequired();
        try (DatabaseProfile persistedProfile = persisted.profile()) {
            assertThat(persisted.revision()).isEqualTo(2L);
            assertThat(persistedProfile.sqlitePath()).isEqualTo(target.toAbsolutePath().normalize());
        }
    }

    @Test
    void rejectsStaleRevisionBeforePreparingTarget() {
        Path target = temporaryDirectory.resolve("revision-conflict.db");

        DatabaseAdministrationException failure = failureOf(() ->
                runtime.switchDatabase(0L, sqliteSettings(target), administrator.username()));

        assertThat(failure.code()).isEqualTo("DATABASE_CONFIG_CONFLICT");
        assertThat(runtime.current().revision()).isEqualTo(1L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(initialDatabase.toAbsolutePath().normalize());
        assertThat(target).doesNotExist();
        assertThat(activeConfiguration).doesNotExist();
        assertThat(beforeActiveDatabaseChanges).hasValue(0);
        assertThat(activeDatabaseChanges).hasValue(0);
    }

    @Test
    void writeVerificationFailureLeavesOldDatabaseActive() throws Exception {
        Path target = temporaryDirectory.resolve("write-failure.db");
        try (DatabaseProfile profile = DatabaseProfile.sqlite(target, Duration.ofSeconds(5));
                DatabaseCandidate ignored = candidateFactory.prepare(profile)) {
            // Candidate preparation creates the managed schema before the write failure is introduced.
        }
        try (Connection connection = SQLiteDataSourceFactory.create(target).getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER reject_bot_probe
                    BEFORE INSERT ON bots
                    BEGIN
                        SELECT RAISE(ABORT, 'writes disabled');
                    END
                    """);
        }

        DatabaseAdministrationException failure = failureOf(() ->
                runtime.switchDatabase(1L, sqliteSettings(target), administrator.username()));

        assertThat(failure.code()).isEqualTo("DATABASE_WRITE_FAILED");
        assertThat(runtime.current().revision()).isEqualTo(1L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(initialDatabase.toAbsolutePath().normalize());
        assertThat(new JdbcAdminUserRepository(runtime.dataSource())
                        .findByUsername(administrator.username()))
                .isPresent();
        assertThat(activeConfiguration).doesNotExist();
        assertThat(beforeActiveDatabaseChanges).hasValue(0);
        assertThat(activeDatabaseChanges).hasValue(0);
    }

    @Test
    void configurationCommitFailureRestoresOldDelegateAndCleansPendingFile() throws Exception {
        Path target = temporaryDirectory.resolve("commit-failure.db");
        Files.createDirectory(activeConfiguration);

        DatabaseAdministrationException failure = failureOf(() ->
                runtime.switchDatabase(1L, sqliteSettings(target), administrator.username()));

        assertThat(failure.code()).isEqualTo("DATABASE_SWITCH_FAILED");
        assertThat(runtime.current().revision()).isEqualTo(1L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(initialDatabase.toAbsolutePath().normalize());
        assertThat(new JdbcAdminUserRepository(runtime.dataSource())
                        .findByUsername(administrator.username()))
                .isPresent();
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("database.json"))
                            .filter(path -> path.getFileName().toString().endsWith(".pending")))
                    .isEmpty();
        }
        assertThat(beforeActiveDatabaseChanges).hasValue(1);
        assertThat(activeDatabaseChanges).hasValue(1);
    }

    @Test
    void concurrentSwitchIsRejectedWhileFirstSwitchDrainsConnections() throws Exception {
        Path firstTarget = temporaryDirectory.resolve("concurrent-first.db");
        Path secondTarget = temporaryDirectory.resolve("concurrent-second.db");
        Connection borrowed = runtime.dataSource().getConnection();
        CompletableFuture<DatabaseSwitchResult> first = CompletableFuture.supplyAsync(() ->
                runtime.switchDatabase(1L, sqliteSettings(firstTarget), administrator.username()));

        try {
            awaitSwitchInProgress();
            DatabaseAdministrationException failure = failureOf(() ->
                    runtime.switchDatabase(1L, sqliteSettings(secondTarget), administrator.username()));
            assertThat(failure.code()).isEqualTo("DATABASE_SWITCH_IN_PROGRESS");
            assertThat(secondTarget).doesNotExist();
            assertThat(activeDatabaseChanges).hasValue(0);
        } finally {
            borrowed.close();
        }

        DatabaseSwitchResult result = first.get(10, TimeUnit.SECONDS);
        assertThat(result.configuration().revision()).isEqualTo(2L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(firstTarget.toAbsolutePath().normalize());
        assertThat(activeDatabaseChanges).hasValue(1);
    }

    @Test
    void reloadUsesCandidateAndBadCandidateCannotPoisonNextStartup() throws Exception {
        Path target = temporaryDirectory.resolve("reload-target.db");
        writeSQLiteCandidate(target);

        DatabaseSwitchResult switched = runtime.reload(1L, administrator.username());
        assertThat(switched.configuration().revision()).isEqualTo(2L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(activeDatabaseChanges).hasValue(1);
        byte[] lastKnownGood = Files.readAllBytes(activeConfiguration);

        Files.writeString(candidateConfiguration, "{ not-valid-json", StandardCharsets.UTF_8);
        DatabaseAdministrationException failure = failureOf(() ->
                runtime.reload(2L, administrator.username()));

        assertThat(failure.code()).isEqualTo("DATABASE_CONFIG_INVALID");
        assertThat(Files.readAllBytes(activeConfiguration)).isEqualTo(lastKnownGood);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(activeDatabaseChanges).hasValue(1);

        runtime.close();
        runtime = new DatabaseRuntime(
                bootstrap(temporaryDirectory.resolve("unused-bootstrap.db")),
                new DatabaseCandidateFactory(),
                configurationStore());
        assertThat(runtime.current().revision()).isEqualTo(2L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(new JdbcAdminUserRepository(runtime.dataSource())
                        .findByUsername(administrator.username()))
                .isPresent();
    }

    @Test
    void notificationFailureCannotRollBackAnActivatedDatabase() {
        runtime.close();
        Path replacementInitial = temporaryDirectory.resolve("notification-initial.db");
        AtomicInteger notificationAttempts = new AtomicInteger();
        runtime = new DatabaseRuntime(
                bootstrap(replacementInitial),
                new DatabaseCandidateFactory(),
                configurationStore(),
                () -> {
                    notificationAttempts.incrementAndGet();
                    throw new IllegalStateException("runtime notification unavailable");
                });
        assertThat(new JdbcAdminUserRepository(runtime.dataSource()).insertFirst(administrator)).isTrue();
        Path target = temporaryDirectory.resolve("notification-target.db");

        DatabaseSwitchResult result =
                runtime.switchDatabase(1L, sqliteSettings(target), administrator.username());

        assertThat(result.configuration().revision()).isEqualTo(2L);
        assertThat(activeDatabasePath(runtime.dataSource())).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(notificationAttempts).hasValue(1);
        DatabaseConfigurationStore.LoadedConfiguration persisted =
                configurationStore.loadRequired();
        try (DatabaseProfile persistedProfile = persisted.profile()) {
            assertThat(persisted.revision()).isEqualTo(2L);
            assertThat(persistedProfile.type()).isEqualTo(DatabaseType.SQLITE);
        }
    }

    private DatabaseBootstrapProperties bootstrap(Path sqliteDatabase) {
        DatabaseBootstrapProperties properties = new DatabaseBootstrapProperties();
        properties.setConfigFile(activeConfiguration);
        properties.setCandidateConfigFile(candidateConfiguration);
        properties.getSqlite().setPath(sqliteDatabase);
        properties.getSqlite().setBusyTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private DatabaseConfigurationStore configurationStore() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        return new DatabaseConfigurationStore(
                activeConfiguration,
                candidateConfiguration,
                OBJECT_MAPPER,
                new AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test-key", key)));
    }

    private void writeSQLiteCandidate(Path target) throws Exception {
        var candidate = OBJECT_MAPPER.createObjectNode();
        candidate.put("type", DatabaseType.SQLITE.name());
        candidate.put("sqlitePath", target.toString());
        candidate.put("busyTimeoutMs", 5_000L);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(candidateConfiguration.toFile(), candidate);
    }

    private void awaitSwitchInProgress() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!runtime.current().switchInProgress()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("database switch did not start within five seconds");
            }
            Thread.sleep(10L);
        }
    }

    private static DatabaseSettings sqliteSettings(Path path) {
        return new DatabaseSettings(
                DatabaseType.SQLITE,
                path.toString(),
                5_000L,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static DatabaseAdministrationException failureOf(Runnable operation) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isInstanceOf(DatabaseAdministrationException.class);
        return (DatabaseAdministrationException) failure;
    }

    private static Path activeDatabasePath(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA database_list")) {
            while (result.next()) {
                if ("main".equals(result.getString("name"))) {
                    return Path.of(result.getString("file")).toAbsolutePath().normalize();
                }
            }
            throw new AssertionError("SQLite main database was not reported");
        } catch (Exception exception) {
            throw new AssertionError("Unable to inspect active SQLite database", exception);
        }
    }

    private static long count(Path database, String table) {
        try (Connection connection = SQLiteDataSourceFactory.create(database).getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) {
                throw new AssertionError("Count query returned no row for " + table);
            }
            return result.getLong(1);
        } catch (Exception exception) {
            throw new AssertionError("Unable to count " + table, exception);
        }
    }
}
