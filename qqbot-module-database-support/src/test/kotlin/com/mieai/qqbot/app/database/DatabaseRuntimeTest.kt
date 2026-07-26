package com.mieai.qqbot.app.database

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.mieai.qqbot.admin.database.DatabaseAdministrationException
import com.mieai.qqbot.admin.database.DatabaseSettings
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.persistence.admin.AdminUser
import com.mieai.qqbot.persistence.admin.JdbcAdminUserRepository
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import com.mieai.qqbot.runtime.security.StaticKeyProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class DatabaseRuntimeTest {
    @TempDir lateinit var temporaryDirectory: Path
    private lateinit var initialDatabase: Path
    private lateinit var activeConfiguration: Path
    private lateinit var candidateConfiguration: Path
    private lateinit var candidateFactory: DatabaseCandidateFactory
    private lateinit var configurationStore: DatabaseConfigurationStore
    private lateinit var runtime: DatabaseRuntime
    private lateinit var administrator: AdminUser
    private lateinit var beforeActiveDatabaseChanges: AtomicInteger
    private lateinit var activeDatabaseChanges: AtomicInteger
    private var runtimeClosed = false

    @BeforeEach
    fun startRuntime() {
        initialDatabase = temporaryDirectory.resolve("initial.db")
        activeConfiguration = temporaryDirectory.resolve("database.json")
        candidateConfiguration = temporaryDirectory.resolve("database-candidate.json")
        candidateFactory = DatabaseCandidateFactory(); configurationStore = configurationStore()
        beforeActiveDatabaseChanges = AtomicInteger(); activeDatabaseChanges = AtomicInteger()
        runtime = DatabaseRuntime(bootstrap(initialDatabase), candidateFactory, configurationStore, java.time.Clock.systemUTC(), { beforeActiveDatabaseChanges.incrementAndGet() }, { activeDatabaseChanges.incrementAndGet() })
        administrator = AdminUser(UUID.fromString("17f37609-8b25-4ee8-92e9-d966d9b7754b"), "rootadmin", "\$2a\$12\$database-runtime-test-password-hash", true, ADMIN_CREATED_AT, ADMIN_CREATED_AT)
        assertThat(JdbcAdminUserRepository(runtime.activeDataSource).insertFirst(administrator)).isTrue()
    }

    @AfterEach fun closeRuntime() { if (!runtimeClosed) runtime.close() }

    @Test
    fun initializesEmptySQLiteTargetAndCopiesOnlyAdministrator() {
        val target = temporaryDirectory.resolve("target.db")
        val result = runtime.switchDatabase(1, sqliteSettings(target), administrator.username)
        assertThat(result.configuration.revision).isEqualTo(2); assertThat(result.verification.adminSeeded).isTrue()
        assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(target.toAbsolutePath().normalize())
        assertThat(count(target, "admin_users")).isEqualTo(1); assertThat(count(target, "bots")).isZero(); assertThat(count(target, "event_inbox")).isZero(); assertThat(count(target, "outbox_jobs")).isZero()
        assertThat(beforeActiveDatabaseChanges).hasValue(1); assertThat(activeDatabaseChanges).hasValue(1)
        val copied = requireNotNull(
            JdbcAdminUserRepository(SQLiteDataSourceFactory.create(target)).findByUsername(administrator.username),
        )
        assertThat(copied.id).isEqualTo(administrator.id); assertThat(copied.passwordHash).isEqualTo(administrator.passwordHash); assertThat(copied.enabled).isEqualTo(administrator.enabled)
        configurationStore.loadRequired().profile.use { profile -> assertThat(configurationStore.loadRequired().revision).isEqualTo(2); assertThat(profile.sqlitePath).isEqualTo(target.toAbsolutePath().normalize()) }
    }

    @Test
    fun movesTheExclusiveSQLiteInstanceLockWhenTheActiveDatabaseChanges() {
        val target = temporaryDirectory.resolve("locked-target.db"); runtime.switchDatabase(1, sqliteSettings(target), administrator.username)
        SQLiteInstanceLock.acquire(initialDatabase).use { assertThat(it).isNotNull(); assertThat(initialDatabase.resolveSibling("${initialDatabase.fileName}.instance.lock")).exists() }
        org.assertj.core.api.Assertions.assertThatThrownBy { SQLiteInstanceLock.acquire(target) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("already owned")
        runtime.close(); runtimeClosed = true
        SQLiteInstanceLock.acquire(target).use { assertThat(it).isNotNull(); assertThat(target.resolveSibling("${target.fileName}.instance.lock")).exists() }
    }

    @Test
    fun rejectsStaleRevisionBeforePreparingTarget() {
        val target = temporaryDirectory.resolve("revision-conflict.db"); val failure = failureOf { runtime.switchDatabase(0, sqliteSettings(target), administrator.username) }
        assertThat(failure.code).isEqualTo("DATABASE_CONFIG_CONFLICT"); assertThat(runtime.current().revision).isEqualTo(1); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(initialDatabase.toAbsolutePath().normalize()); assertThat(target).doesNotExist(); assertThat(activeConfiguration).doesNotExist(); assertThat(beforeActiveDatabaseChanges).hasValue(0); assertThat(activeDatabaseChanges).hasValue(0)
    }

    @Test
    fun writeVerificationFailureLeavesOldDatabaseActive() {
        val target = temporaryDirectory.resolve("write-failure.db")
        DatabaseProfile.sqlite(target, Duration.ofSeconds(5)).use { profile -> candidateFactory.prepare(profile).use { } }
        SQLiteDataSourceFactory.create(target).connection.use { connection -> connection.createStatement().use { it.execute("""CREATE TRIGGER reject_bot_probe BEFORE INSERT ON bots BEGIN SELECT RAISE(ABORT, 'writes disabled'); END""") } }
        val failure = failureOf { runtime.switchDatabase(1, sqliteSettings(target), administrator.username) }
        assertThat(failure.code).isEqualTo("DATABASE_WRITE_FAILED"); assertThat(runtime.current().revision).isEqualTo(1); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(initialDatabase.toAbsolutePath().normalize()); assertThat(JdbcAdminUserRepository(runtime.activeDataSource).findByUsername(administrator.username)).isNotNull(); assertThat(activeConfiguration).doesNotExist(); assertThat(beforeActiveDatabaseChanges).hasValue(0); assertThat(activeDatabaseChanges).hasValue(0)
    }

    @Test
    fun configurationCommitFailureRestoresOldDelegateAndCleansPendingFile() {
        val target = temporaryDirectory.resolve("commit-failure.db"); Files.createDirectory(activeConfiguration)
        val failure = failureOf { runtime.switchDatabase(1, sqliteSettings(target), administrator.username) }
        assertThat(failure.code).isEqualTo("DATABASE_SWITCH_FAILED"); assertThat(runtime.current().revision).isEqualTo(1); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(initialDatabase.toAbsolutePath().normalize()); assertThat(JdbcAdminUserRepository(runtime.activeDataSource).findByUsername(administrator.username)).isNotNull()
        Files.list(temporaryDirectory).use { files -> assertThat(files.filter { it.fileName.toString().startsWith("database.json") }.filter { it.fileName.toString().endsWith(".pending") }).isEmpty() }
        assertThat(beforeActiveDatabaseChanges).hasValue(1); assertThat(activeDatabaseChanges).hasValue(1)
    }

    @Test
    fun concurrentSwitchIsRejectedWhileFirstSwitchDrainsConnections() {
        val firstTarget = temporaryDirectory.resolve("concurrent-first.db"); val secondTarget = temporaryDirectory.resolve("concurrent-second.db"); val borrowed = runtime.activeDataSource.connection
        val first = CompletableFuture.supplyAsync { runtime.switchDatabase(1, sqliteSettings(firstTarget), administrator.username) }
        try { awaitSwitchInProgress(); val failure = failureOf { runtime.switchDatabase(1, sqliteSettings(secondTarget), administrator.username) }; assertThat(failure.code).isEqualTo("DATABASE_SWITCH_IN_PROGRESS"); assertThat(secondTarget).doesNotExist(); assertThat(activeDatabaseChanges).hasValue(0) } finally { borrowed.close() }
        val result = first.get(10, TimeUnit.SECONDS); assertThat(result.configuration.revision).isEqualTo(2); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(firstTarget.toAbsolutePath().normalize()); assertThat(activeDatabaseChanges).hasValue(1)
    }

    @Test
    fun reloadUsesCandidateAndBadCandidateCannotPoisonNextStartup() {
        val target = temporaryDirectory.resolve("reload-target.db"); writeSQLiteCandidate(target)
        val switched = runtime.reload(1, administrator.username); assertThat(switched.configuration.revision).isEqualTo(2); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(target.toAbsolutePath().normalize()); assertThat(activeDatabaseChanges).hasValue(1)
        val lastKnownGood = Files.readAllBytes(activeConfiguration); Files.writeString(candidateConfiguration, "{ not-valid-json", StandardCharsets.UTF_8)
        val failure = failureOf { runtime.reload(2, administrator.username) }
        assertThat(failure.code).isEqualTo("DATABASE_CONFIG_INVALID"); assertThat(Files.readAllBytes(activeConfiguration)).isEqualTo(lastKnownGood); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(target.toAbsolutePath().normalize()); assertThat(activeDatabaseChanges).hasValue(1)
        runtime.close(); runtimeClosed = true; runtime = DatabaseRuntime(bootstrap(temporaryDirectory.resolve("unused-bootstrap.db")), DatabaseCandidateFactory(), configurationStore()); runtimeClosed = false
        assertThat(runtime.current().revision).isEqualTo(2); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(target.toAbsolutePath().normalize()); assertThat(JdbcAdminUserRepository(runtime.activeDataSource).findByUsername(administrator.username)).isNotNull()
    }

    @Test
    fun notificationFailureCannotRollBackAnActivatedDatabase() {
        runtime.close(); runtimeClosed = true; val replacementInitial = temporaryDirectory.resolve("notification-initial.db"); val notificationAttempts = AtomicInteger()
        runtime = DatabaseRuntime(bootstrap(replacementInitial), DatabaseCandidateFactory(), configurationStore(), activeDatabaseChanged = { notificationAttempts.incrementAndGet(); throw IllegalStateException("runtime notification unavailable") }); runtimeClosed = false
        assertThat(JdbcAdminUserRepository(runtime.activeDataSource).insertFirst(administrator)).isTrue(); val target = temporaryDirectory.resolve("notification-target.db")
        val result = runtime.switchDatabase(1, sqliteSettings(target), administrator.username)
        assertThat(result.configuration.revision).isEqualTo(2); assertThat(activeDatabasePath(runtime.activeDataSource)).isEqualTo(target.toAbsolutePath().normalize()); assertThat(notificationAttempts).hasValue(1)
        configurationStore.loadRequired().profile.use { profile -> assertThat(configurationStore.loadRequired().revision).isEqualTo(2); assertThat(profile.type).isEqualTo(DatabaseType.SQLITE) }
    }

    private fun bootstrap(sqliteDatabase: Path) = DatabaseBootstrapProperties().also { properties -> properties.configFile = activeConfiguration; properties.candidateConfigFile = candidateConfiguration; properties.sqlite.path = sqliteDatabase; properties.sqlite.busyTimeout = Duration.ofSeconds(5) }
    private fun configurationStore(): DatabaseConfigurationStore { val key = ByteArray(32); Arrays.fill(key, 0x5a.toByte()); return DatabaseConfigurationStore(activeConfiguration, candidateConfiguration, OBJECT_MAPPER, AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test-key", key))) }
    private fun writeSQLiteCandidate(target: Path) { val candidate = OBJECT_MAPPER.createObjectNode(); candidate.put("type", DatabaseType.SQLITE.name); candidate.put("sqlitePath", target.toString()); candidate.put("busyTimeoutMs", 5_000L); OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(candidateConfiguration.toFile(), candidate) }
    private fun awaitSwitchInProgress() { val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); while (!runtime.current().switchInProgress) { if (System.nanoTime() >= deadline) throw AssertionError("database switch did not start within five seconds"); Thread.sleep(10) } }
    private fun sqliteSettings(path: Path) = DatabaseSettings(DatabaseType.SQLITE, path.toString(), 5_000, null, null, null, null, null, null, null)
    private fun failureOf(operation: () -> Unit): DatabaseAdministrationException { val failure = catchThrowable(operation); assertThat(failure).isInstanceOf(DatabaseAdministrationException::class.java); return failure as DatabaseAdministrationException }
    private fun activeDatabasePath(dataSource: DataSource): Path = try { dataSource.connection.use { connection -> connection.createStatement().use { statement -> statement.executeQuery("PRAGMA database_list").use { result -> while (result.next()) if (result.getString("name") == "main") return Path.of(result.getString("file")).toAbsolutePath().normalize(); throw AssertionError("SQLite main database was not reported") } } } } catch (exception: Exception) { throw AssertionError("Unable to inspect active SQLite database", exception) }
    private fun count(database: Path, table: String): Long = try { SQLiteDataSourceFactory.create(database).connection.use { connection -> connection.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM $table").use { result -> if (!result.next()) throw AssertionError("Count query returned no row for $table"); result.getLong(1) } } } } catch (exception: Exception) { throw AssertionError("Unable to count $table", exception) }
    companion object { private val ADMIN_CREATED_AT = Instant.parse("2026-01-02T03:04:05Z"); private val OBJECT_MAPPER: ObjectMapper = JsonMapper.builder().findAndAddModules().build() }
}
