package com.mieai.qqbot.persistence.sqlite

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository
import com.mieai.qqbot.persistence.outbox.NewOutboxJob
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifact
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatNullPointerException
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class SQLiteMigrationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun migratesARealStrictSQLiteDatabaseIdempotently() {
        val databaseFile = temporaryDirectory.resolve("database with space.db")
        val dataSource = SQLiteDataSourceFactory.create(databaseFile)
        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isEqualTo(15)
        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isZero()
        assertThat(databaseFile).isRegularFile()
        dataSource.connection.use { connection ->
            assertThat(queryInt(connection, """SELECT count(*) FROM pragma_table_list WHERE schema = 'main' AND name IN ('admin_users', 'admin_login_attempts', 'audit_logs', 'bot_leases', 'bots', 'event_inbox', 'outbox_jobs', 'plugin_artifacts', 'bot_plugins', 'plugin_deliveries', 'plugin_storage', 'instance_plugin_hashes') AND strict = 1""")).isEqualTo(12)
            assertThat(queryString(connection, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'bots'"))
                .contains("UNIQUE (environment, app_id)", "app_secret_ciphertext", "app_secret_key_id", "max_media_upload_bytes", "revision")
            assertThat(queryInt(connection, "SELECT count(*) FROM pragma_table_info('bot_plugins') WHERE name = 'config_json'")).isZero()
            assertThat(queryInt(connection, "SELECT count(*) FROM pragma_table_info('outbox_jobs') WHERE name IN ('producer_binding_id', 'platform_message_id', 'platform_message_sequence', 'platform_timestamp')")).isEqualTo(4)
        }
    }

    @Test fun configuresWalForeignKeysAndBusyTimeoutOnConnections() {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("settings.db")); SQLiteDatabaseInitializer.migrate(dataSource)
        dataSource.connection.use { first -> dataSource.connection.use { second ->
            assertThat(queryString(first, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal")
            assertThat(queryInt(first, "PRAGMA foreign_keys")).isEqualTo(1); assertThat(queryInt(first, "PRAGMA busy_timeout")).isEqualTo(5_000)
            assertThat(queryInt(second, "PRAGMA foreign_keys")).isEqualTo(1); assertThat(queryInt(second, "PRAGMA busy_timeout")).isEqualTo(5_000)
        } }
    }

    @Test fun appliesCustomBusyTimeoutAndValidatesFactoryArguments() {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("custom-timeout.db"), Duration.ofMillis(275))
        dataSource.connection.use { assertThat(queryInt(it, "PRAGMA busy_timeout")).isEqualTo(275) }
        assertThatNullPointerException().isThrownBy { invokeFactory(null) }
        assertThatNullPointerException().isThrownBy { invokeFactory(temporaryDirectory.resolve("null-timeout.db"), null) }
        assertThatIllegalArgumentException().isThrownBy { SQLiteDataSourceFactory.create(temporaryDirectory.resolve("zero-timeout.db"), Duration.ZERO) }
        assertThatIllegalArgumentException().isThrownBy { SQLiteDataSourceFactory.create(temporaryDirectory.resolve("large-timeout.db"), Duration.ofMillis(Int.MAX_VALUE.toLong() + 1)) }
        assertThatNullPointerException().isThrownBy { invokeMigration(null) }
    }

    @Test fun createsOnlyTheExpectedApplicationTablesThroughV015() {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("schema.db")); SQLiteDatabaseInitializer.migrate(dataSource)
        val expected = listOf("SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES", "admin_login_attempts", "admin_users", "audit_logs", "bot_leases", "bot_plugins", "bots", "event_inbox", "instance_plugin_hashes", "outbox_jobs", "plugin_artifacts", "plugin_deliveries", "plugin_storage")
        dataSource.connection.use { connection -> connection.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'flyway_%' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { resultSet ->
            expected.forEach { name -> assertThat(resultSet.next()).isTrue(); assertThat(resultSet.getString(1)).isEqualTo(name) }; assertThat(resultSet.next()).isFalse()
        } } }
        assertThat(Files.exists(temporaryDirectory.resolve("schema.db"))).isTrue()
    }

    @Test fun preservesOutboxHistoryAndClearsProducerWhenBindingIsDeleted() {
        val botId = "550e8400-e29b-41d4-a716-446655440001"; val bindingId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001"); val jobId = UUID.fromString("880e8400-e29b-41d4-a716-446655440001")
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("binding-delete.db")); SQLiteDatabaseInitializer.migrate(dataSource); insertBot(dataSource, botId, "10001", BotEnvironment.SANDBOX)
        JdbcPluginArtifactRepository(dataSource).upsert(PluginArtifact("echo", "Echo Reply", "1.0.0", "1.0.0", "echo.jar", "sha256", "factory", "LOADED", true, BASE_TIME, BASE_TIME))
        val bindings = JdbcBotPluginBindingRepository(dataSource); bindings.insert(BotPluginBinding(bindingId, "echo", BotId.parse(botId), true, 0, BASE_TIME, BASE_TIME, PluginBindingRuntimeState.ACTIVE, null))
        val outbox = JdbcOutboxRepository(dataSource); outbox.create(NewOutboxJob(jobId, BotEnvironment.SANDBOX, BotId.parse(botId), null, "SEND_MESSAGE", "binding-delete", "{}", BASE_TIME, BASE_TIME, bindingId))
        bindings.delete(bindingId)
        assertThat(requireNotNull(outbox.findById(jobId)).producerBindingId).isNull()
    }

    @Test fun dropsTheLegacyDatabaseBackedPluginConfigurationInV014() {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("legacy-plugin-config.db"))
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/sqlite").target("13").load().migrate()
        dataSource.connection.use { assertThat(queryInt(it, "SELECT count(*) FROM pragma_table_info('bot_plugins') WHERE name = 'config_json'")).isEqualTo(1) }
        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isEqualTo(2)
        dataSource.connection.use { assertThat(queryInt(it, "SELECT count(*) FROM pragma_table_info('bot_plugins') WHERE name = 'config_json'")).isZero() }
    }

    @Test fun upgradesLegacyZeroIntentsToTheCurrentGroupAndC2cDefault() {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("legacy.db")); Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/sqlite").target("3").load().migrate()
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate("""INSERT INTO bots (id, display_name, app_id, environment, app_secret_ciphertext, app_secret_key_id, intents, shard_index, shard_count, enabled, revision, created_at, updated_at) VALUES ('550e8400-e29b-41d4-a716-446655440001', 'Legacy Bot', '1905208810', 'PRODUCTION', 'ciphertext', 'primary', 0, 0, 1, 1, 1, '2026-07-17T12:00:00Z', '2026-07-17T12:00:00Z')""") } }
        assertThat(SQLiteDatabaseInitializer.migrate(dataSource)).isEqualTo(12)
        dataSource.connection.use { assertThat(queryInt(it, "SELECT intents FROM bots")).isEqualTo(33_554_432); assertThat(queryInt(it, "SELECT revision FROM bots")).isEqualTo(2) }
    }

    private fun queryInt(connection: Connection, sql: String) = connection.createStatement().use { statement -> statement.executeQuery(sql).use { results -> assertThat(results.next()).isTrue(); results.getInt(1) } }
    private fun queryString(connection: Connection, sql: String) = connection.createStatement().use { statement -> statement.executeQuery(sql).use { results -> assertThat(results.next()).isTrue(); results.getString(1) } }
    private fun invokeFactory(databaseFile: Path?, busyTimeout: Duration? = SQLiteDataSourceFactory.DEFAULT_BUSY_TIMEOUT) = invoke(
        SQLiteDataSourceFactory::class.java.getMethod("create", Path::class.java, Duration::class.java),
        SQLiteDataSourceFactory,
        databaseFile,
        busyTimeout,
    )
    private fun invokeMigration(dataSource: DataSource?) = invoke(
        SQLiteDatabaseInitializer::class.java.getMethod("migrate", DataSource::class.java),
        SQLiteDatabaseInitializer,
        dataSource,
    )
    private fun invoke(method: java.lang.reflect.Method, target: Any, vararg arguments: Any?) {
        try { method.invoke(target, *arguments) } catch (exception: InvocationTargetException) { throw exception.cause!! }
    }
}
