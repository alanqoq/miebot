package com.mieai.qqbot.persistence.lease

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifact
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class JdbcBotLeaseRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun preventsLiveOwnerTakeoverAndFencesExpiredOwner() {
        val dataSource = migratedDatabase(directory.resolve("leases.db"))
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX)
        val repository = JdbcBotLeaseRepository(dataSource)
        val botId = BotId.parse(BOT)
        val duration = Duration.ofSeconds(30)

        val first = requireNotNull(repository.acquire(botId, 0, "instance-a", BASE_TIME, duration))
        assertThat(first.fencingToken).isEqualTo(1)
        assertThat(repository.acquire(botId, 0, "instance-b", BASE_TIME.plusSeconds(1), duration)).isNull()
        assertThat(repository.renew(first, BASE_TIME.plusSeconds(5), duration)).isTrue()

        val takeover = requireNotNull(repository.acquire(
            botId,
            0,
            "instance-b",
            BASE_TIME.plusSeconds(36),
            duration,
        ))
        assertThat(takeover.fencingToken).isGreaterThan(first.fencingToken)
        assertThat(repository.renew(first, BASE_TIME.plusSeconds(37), duration)).isFalse()
        assertThat(repository.release(first)).isFalse()
        assertThat(repository.release(takeover)).isTrue()
    }

    @Test
    fun admitsOnlyInstancesWithTheRequiredPluginArtifactHash() {
        val dataSource = migratedDatabase(directory.resolve("plugin-hash-leases.db"))
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX)
        val artifactRepository = JdbcPluginArtifactRepository(dataSource)
        artifactRepository.upsert(
            PluginArtifact(
                "echo", "Echo", "1.0.0", "2.0.0", "echo.jar", "correct-hash",
                "com.example.Echo", "LOADED", true, BASE_TIME, BASE_TIME,
            ),
        )
        JdbcBotPluginBindingRepository(dataSource).insert(
            binding(UUID.fromString("550e8400-e29b-41d4-a716-446655440010"), BOT),
        )
        val repository = JdbcBotLeaseRepository(dataSource)
        val botId = BotId.parse(BOT)
        val duration = Duration.ofSeconds(30)

        assertThat(
            repository.acquire(botId, 0, "instance-a", BASE_TIME, duration, mapOf("echo" to "wrong-hash")),
        ).isNull()
        assertThat(repository.isOwned(botId, 0, "instance-a", BASE_TIME.plusSeconds(1))).isFalse()

        val acquired = requireNotNull(repository.acquire(
            botId,
            0,
            "instance-a",
            BASE_TIME,
            duration,
            mapOf("echo" to "correct-hash"),
        ))
        assertThat(repository.isOwned(botId, 0, "instance-a", BASE_TIME.plusSeconds(1))).isTrue()
        assertThat(repository.isOwned(botId, "instance-a", BASE_TIME.plusSeconds(1))).isTrue()
        assertThat(repository.isOwned(botId, "instance-b", BASE_TIME.plusSeconds(1))).isFalse()

        artifactRepository.upsert(
            PluginArtifact(
                "echo", "Echo", "1.1.0", "2.0.0", "echo.jar", "replacement-hash",
                "com.example.Echo", "LOADED", true, BASE_TIME, BASE_TIME.plusSeconds(2),
            ),
        )
        assertThat(
            repository.renew(acquired, BASE_TIME.plusSeconds(5), duration, mapOf("echo" to "correct-hash")),
        ).isFalse()
        assertThat(repository.release(acquired)).isTrue()
    }

    @Test
    fun rejectsAndStopsRenewingLeasesForANonCurrentShard() {
        val dataSource = migratedDatabase(directory.resolve("current-shard-leases.db"))
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX)
        val repository = JdbcBotLeaseRepository(dataSource)
        val botId = BotId.parse(BOT)
        val duration = Duration.ofSeconds(30)
        val oldShard = requireNotNull(repository.acquire(botId, 0, "instance-a", BASE_TIME, duration))

        JdbcTemplate(dataSource).update("UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT)

        assertThat(repository.renew(oldShard, BASE_TIME.plusSeconds(1), duration)).isFalse()
        assertThat(repository.isOwned(botId, 0, "instance-a", BASE_TIME.plusSeconds(1))).isFalse()
        assertThat(repository.isOwned(botId, "instance-a", BASE_TIME.plusSeconds(1))).isFalse()
        assertThat(repository.acquire(botId, 0, "instance-b", BASE_TIME.plusSeconds(1), duration)).isNull()

        val current = requireNotNull(repository.acquire(
            botId,
            1,
            "instance-b",
            BASE_TIME.plusSeconds(1),
            duration,
        ))
        assertThat(repository.isOwned(botId, 1, "instance-b", BASE_TIME.plusSeconds(2))).isTrue()
        assertThat(repository.release(current)).isTrue()
    }

    private fun binding(id: UUID, botId: String) = BotPluginBinding(
        id,
        "echo",
        BotId.parse(botId),
        true,
        0L,
        BASE_TIME,
        BASE_TIME,
        PluginBindingRuntimeState.ACTIVE,
        null,
    )

    companion object {
        private const val BOT = "550e8400-e29b-41d4-a716-446655440001"
    }
}
