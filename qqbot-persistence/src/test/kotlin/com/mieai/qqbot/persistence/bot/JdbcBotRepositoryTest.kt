package com.mieai.qqbot.persistence.bot

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.dao.DataAccessException
import java.nio.file.Path
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcBotRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var dataSource: DataSource
    private lateinit var repository: BotRepository

    @BeforeEach
    fun setUp() {
        dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("repository.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        repository = JdbcBotRepository(dataSource)
    }

    @Test
    fun insertsAndFindsACompleteStoredBot() {
        val bot = bot("550e8400-e29b-41d4-a716-446655440000", "10001", true)
        repository.insert(bot)
        assertThat(repository.findById(bot.id)).isEqualTo(bot)
        assertThat(repository.findById(BotId.parse("550e8400-e29b-41d4-a716-446655440099"))).isNull()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT created_at, updated_at FROM bots WHERE id = '${bot.id}'").use { resultSet ->
                    assertThat(resultSet.next()).isTrue()
                    assertThat(resultSet.getString("created_at")).endsWith("Z")
                    assertThat(resultSet.getString("updated_at")).endsWith("Z")
                }
            }
        }
    }

    @Test
    fun findsAllBotsIncludingDisabledInStableOrder() {
        val laterEnabled = bot("550e8400-e29b-41d4-a716-446655440002", "10002", true)
        val disabled = bot("550e8400-e29b-41d4-a716-446655440003", "10003", false)
        val earlierEnabled = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true)
        repository.insert(laterEnabled); repository.insert(disabled); repository.insert(earlierEnabled)
        val all = repository.findAll()
        assertThat(all).containsExactly(earlierEnabled, laterEnabled, disabled)
    }

    @Test
    fun findsOnlyEnabledBotsInStableOrder() {
        val laterEnabled = bot("550e8400-e29b-41d4-a716-446655440002", "10002", true)
        val disabled = bot("550e8400-e29b-41d4-a716-446655440003", "10003", false)
        val earlierEnabled = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true)
        repository.insert(laterEnabled); repository.insert(disabled); repository.insert(earlierEnabled)
        val enabled = repository.findEnabled()
        assertThat(enabled).containsExactly(earlierEnabled, laterEnabled)
    }

    @Test
    fun enforcesEnvironmentAndAppIdUniqueness() {
        repository.insert(bot("550e8400-e29b-41d4-a716-446655440001", "10001", true))
        assertThatThrownBy { repository.insert(bot("550e8400-e29b-41d4-a716-446655440002", "10001", false)) }
            .isInstanceOf(DataAccessException::class.java)
        val sameAppIdInProduction = StoredBot(
            definition("550e8400-e29b-41d4-a716-446655440003", "10001", true, BotEnvironment.PRODUCTION, BotRevision.initial(), CREATED_AT),
            SecretCiphertext.of("v1:ciphertext-10001-production", "master-key-v1"),
        )
        repository.insert(sameAppIdInProduction)
        assertThat(repository.findById(sameAppIdInProduction.id)).isEqualTo(sameAppIdInProduction)
    }

    @Test
    fun updatesWithAnExpectedRevisionAndRejectsAStaleWriter() {
        val original = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true)
        repository.insert(original)
        val desired = StoredBot(BotDefinition(original.id, "Renamed Bot", original.definition.appId, original.definition.environment,
            GatewayIntents.of(1L shl 26), ShardSpec(1, 2), false, BotRevision.initial(), CREATED_AT, CREATED_AT.plusSeconds(30)),
            SecretCiphertext.of("v2:rotated-ciphertext", "master-key-v2"))
        val updatedRevision = repository.update(desired, BotRevision.initial())
        assertThat(updatedRevision).isEqualTo(BotRevision.of(2))
        val stored = requireNotNull(repository.findById(original.id))
        assertThat(stored.definition.displayName).isEqualTo("Renamed Bot")
        assertThat(stored.definition.revision).isEqualTo(BotRevision.of(2))
        assertThat(stored.definition.enabled).isFalse()
        assertThat(stored.definition.shardSpec).isEqualTo(ShardSpec(1, 2))
        assertThat(stored.appSecret).isEqualTo(desired.appSecret)
        assertThatThrownBy { repository.update(desired, BotRevision.initial()) }
            .isInstanceOfSatisfying(OptimisticLockException::class.java) { exception ->
                assertThat(exception.botId).isEqualTo(original.id)
                assertThat(exception.expectedRevision).isEqualTo(BotRevision.initial())
            }
        assertThat(requireNotNull(repository.findById(original.id)).definition.revision).isEqualTo(BotRevision.of(2))
    }

    @Test
    fun validatesCandidateRevisionBeforeUpdating() {
        val original = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true)
        repository.insert(original)
        val inconsistent = StoredBot(definition(original.id.toString(), "10001", true, BotEnvironment.SANDBOX, BotRevision.of(2), CREATED_AT), original.appSecret)
        assertThatIllegalArgumentException().isThrownBy { repository.update(inconsistent, BotRevision.initial()) }.withMessageContaining("revision")
    }

    @Test
    fun reportsMissingBotAsOptimisticLockFailure() {
        val missing = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true)
        assertThatThrownBy { repository.update(missing, BotRevision.initial()) }.isInstanceOf(OptimisticLockException::class.java)
    }

    @Test
    fun deletesBotOwnedInboxAndOutboxHistoryTransactionally() {
        val stored = bot("550e8400-e29b-41d4-a716-446655440001", "10001", true)
        repository.insert(stored)
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeUpdate("""INSERT INTO event_inbox (id, environment, bot_id, event_type, platform_event_id, payload, status, attempt, available_at, fencing_token, received_at, updated_at) VALUES ('660e8400-e29b-41d4-a716-446655440001','SANDBOX','550e8400-e29b-41d4-a716-446655440001','MESSAGE_CREATE','event-1','{}','RECEIVED',0,'2026-07-16T12:00:00Z',0,'2026-07-16T12:00:00Z','2026-07-16T12:00:00Z')""")
            statement.executeUpdate("""INSERT INTO outbox_jobs (id, environment, bot_id, source_event_id, job_type, payload, status, attempt, available_at, fencing_token, created_at, updated_at) VALUES ('770e8400-e29b-41d4-a716-446655440001','SANDBOX','550e8400-e29b-41d4-a716-446655440001','660e8400-e29b-41d4-a716-446655440001','TEST','{}','PENDING',0,'2026-07-16T12:00:00Z',0,'2026-07-16T12:00:00Z','2026-07-16T12:00:00Z')""")
        } }
        assertThat(repository.delete(stored.id)).isTrue()
        assertThat(repository.findById(stored.id)).isNull()
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            assertThat(queryCount(statement, "event_inbox")).isZero(); assertThat(queryCount(statement, "outbox_jobs")).isZero()
        } }
    }

    private fun queryCount(statement: Statement, table: String): Int = statement.executeQuery("SELECT COUNT(*) FROM $table").use { if (it.next()) it.getInt(1) else -1 }
    private fun bot(id: String, appId: String, enabled: Boolean) = StoredBot(definition(id, appId, enabled, BotEnvironment.SANDBOX, BotRevision.initial(), CREATED_AT), SecretCiphertext.of("v1:ciphertext-$appId", "master-key-v1"))
    private fun definition(id: String, appId: String, enabled: Boolean, environment: BotEnvironment, revision: BotRevision, createdAt: Instant) = BotDefinition(BotId.of(UUID.fromString(id)), "Bot $appId", QqAppId.of(appId), environment, GatewayIntents.of(512L), ShardSpec.single(), enabled, revision, createdAt, createdAt.plusSeconds(5))

    companion object { private val CREATED_AT = Instant.parse("2026-07-16T12:00:00Z") }
}
