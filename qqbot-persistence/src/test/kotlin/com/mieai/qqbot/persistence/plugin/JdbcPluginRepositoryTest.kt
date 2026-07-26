package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.inbox.IncomingEvent
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class JdbcPluginRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var dataSource: DataSource

    @BeforeEach
    fun setUp() {
        dataSource = migratedDatabase(temporaryDirectory.resolve("plugins.db"))
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX)
    }

    @Test
    fun persistsBindingRevisionAndFencedDeliveryLifecycle() {
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        val bindings = JdbcBotPluginBindingRepository(dataSource)
        val deliveries = JdbcPluginDeliveryRepository(dataSource)
        artifacts.upsert(artifact("1.0.0", "1.0.0"))

        val binding = binding(BINDING, BOT, true, 0, BASE_TIME)
        bindings.insert(binding)
        assertThat(bindings.findByPluginAndBot("echo", BotId.parse(BOT))).isEqualTo(binding)
        val updated = bindings.update(binding(BINDING, BOT, false, 0, BASE_TIME.plusSeconds(1)), 0)
        assertThat(updated.revision).isEqualTo(1)
        assertThat(updated.enabled).isFalse()

        JdbcEventInboxRepository(dataSource).insertOrGet(
            IncomingEvent(
                EVENT,
                BotEnvironment.SANDBOX,
                BotId.parse(BOT),
                "MESSAGE_CREATE",
                "event-1",
                "{}",
                BASE_TIME,
            ),
        )

        assertThat(deliveries.createIfAbsent(DELIVERY, EVENT, BINDING, "default", BASE_TIME)).isTrue()
        assertThat(deliveries.createIfAbsent(UUID.randomUUID(), EVENT, BINDING, "default", BASE_TIME)).isFalse()
        assertThat(deliveries.claimNext("worker", BASE_TIME, Duration.ofSeconds(30))).isNull()
        assertThat(deliveries.pauseForBinding(BINDING, BASE_TIME.plusSeconds(1), "binding disabled")).isEqualTo(1)
        assertThat(requireNotNull(deliveries.findById(DELIVERY)).status).isEqualTo(PluginDeliveryStatus.PAUSED)
        bindings.update(binding(BINDING, BOT, true, 1, BASE_TIME.plusSeconds(2)), 1)
        assertThat(deliveries.resumeForBinding(BINDING, BASE_TIME.plusSeconds(2))).isEqualTo(1)
        val claimed = requireNotNull(deliveries.claimNext(
            "worker",
            BASE_TIME.plusSeconds(3),
            Duration.ofSeconds(30),
        ))
        assertThat(claimed.attempt).isEqualTo(1)
        deliveries.markSucceeded(claimed.id, claimed.fencingToken, BASE_TIME.plusSeconds(4))
        assertThat(deliveries.claimNext("worker-2", BASE_TIME.plusSeconds(5), Duration.ofSeconds(30))).isNull()
        assertThat(requireNotNull(deliveries.findById(DELIVERY)).status).isEqualTo(PluginDeliveryStatus.SUCCEEDED)
        assertThat(
            deliveries.query(
                PluginDeliveryQuery(
                    10,
                    null,
                    BINDING,
                    PluginDeliveryStatus.SUCCEEDED,
                    "default",
                ),
            ).deliveries,
        ).hasSize(1)
        assertThat(deliveries.statistics()).isEqualTo(PluginDeliveryQueueStats(1, 0, 0, 0, 1, 0, 0))
    }

    @Test
    fun touchesBindingAfterAFileChangeWithOptimisticLocking() {
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        val bindings = JdbcBotPluginBindingRepository(dataSource)
        artifacts.upsert(artifact("1.0.0", "1.0.0"))
        bindings.insert(binding(BINDING, BOT, true, 0, BASE_TIME))

        val touched = bindings.touch(BINDING, 0, BASE_TIME.plusSeconds(1))

        assertThat(touched.revision).isEqualTo(1)
        assertThat(touched.updatedAt).isEqualTo(BASE_TIME.plusSeconds(1))
        assertThat(touched.enabled).isTrue()
        assertThatThrownBy { bindings.touch(BINDING, 0, BASE_TIME.plusSeconds(2)) }
            .isInstanceOf(PluginBindingOptimisticLockException::class.java)
    }

    @Test
    fun ownedClaimSelectsOnlyDeliveriesForTheInstanceBotLease() {
        val secondBot = "550e8400-e29b-41d4-a716-446655440002"
        val secondEvent = UUID.fromString("660e8400-e29b-41d4-a716-446655440002")
        val secondBinding = UUID.fromString("770e8400-e29b-41d4-a716-446655440002")
        val secondDelivery = UUID.fromString("880e8400-e29b-41d4-a716-446655440002")
        insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX)
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        val bindings = JdbcBotPluginBindingRepository(dataSource)
        val inbox = JdbcEventInboxRepository(dataSource)
        val deliveries = JdbcPluginDeliveryRepository(dataSource)
        artifacts.upsert(artifact("1.0.0", "2.0.0"))
        bindings.insert(binding(BINDING, BOT, true, 0, BASE_TIME))
        bindings.insert(binding(secondBinding, secondBot, true, 0, BASE_TIME))
        inbox.insertOrGet(incoming(EVENT, BOT, "event-other"))
        inbox.insertOrGet(incoming(secondEvent, secondBot, "event-owned"))
        deliveries.createIfAbsent(DELIVERY, EVENT, BINDING, "default", BASE_TIME)
        deliveries.createIfAbsent(secondDelivery, secondEvent, secondBinding, "default", BASE_TIME)
        requireNotNull(JdbcBotLeaseRepository(dataSource).acquire(
            BotId.parse(secondBot),
            0,
            "instance-b",
            BASE_TIME,
            Duration.ofSeconds(30),
        ))

        val claimed = requireNotNull(deliveries.claimNextOwned(
            "plugin-worker",
            "instance-b",
            BASE_TIME,
            Duration.ofSeconds(10),
        ))

        assertThat(claimed.id).isEqualTo(secondDelivery)
        assertThat(requireNotNull(deliveries.findById(DELIVERY)).status).isEqualTo(PluginDeliveryStatus.PENDING)
    }

    @Test
    fun ownedClaimIgnoresALeaseForTheBotsPreviousShard() {
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        val bindings = JdbcBotPluginBindingRepository(dataSource)
        val inbox = JdbcEventInboxRepository(dataSource)
        val deliveries = JdbcPluginDeliveryRepository(dataSource)
        artifacts.upsert(artifact("1.0.0", "2.0.0"))
        bindings.insert(binding(BINDING, BOT, true, 0, BASE_TIME))
        inbox.insertOrGet(incoming(EVENT, BOT, "event-stale-shard"))
        deliveries.createIfAbsent(DELIVERY, EVENT, BINDING, "default", BASE_TIME)
        requireNotNull(JdbcBotLeaseRepository(dataSource).acquire(
            BotId.parse(BOT),
            0,
            "instance-b",
            BASE_TIME,
            Duration.ofSeconds(30),
        ))
        JdbcTemplate(dataSource).update("UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT)

        assertThat(
            deliveries.claimNextOwned("plugin-worker", "instance-b", BASE_TIME, Duration.ofSeconds(10)),
        ).isNull()
        assertThat(requireNotNull(deliveries.findById(DELIVERY)).status).isEqualTo(PluginDeliveryStatus.PENDING)
    }

    private fun artifact(version: String, compatibility: String) = PluginArtifact(
        "echo", "Echo Reply", version, compatibility, "echo.jar", "abc", "factory",
        "LOADED", true, BASE_TIME, BASE_TIME,
    )

    private fun binding(id: UUID, botId: String, enabled: Boolean, revision: Long, updatedAt: java.time.Instant) =
        BotPluginBinding(
            id,
            "echo",
            BotId.parse(botId),
            enabled,
            revision,
            BASE_TIME,
            updatedAt,
            if (enabled) PluginBindingRuntimeState.ACTIVE else PluginBindingRuntimeState.PAUSED,
            null,
        )

    private fun incoming(id: UUID, botId: String, platformEventId: String) = IncomingEvent(
        id,
        BotEnvironment.SANDBOX,
        BotId.parse(botId),
        "MESSAGE_CREATE",
        platformEventId,
        "{}",
        BASE_TIME,
    )

    companion object {
        private const val BOT = "550e8400-e29b-41d4-a716-446655440001"
        private val EVENT = UUID.fromString("660e8400-e29b-41d4-a716-446655440001")
        private val BINDING = UUID.fromString("770e8400-e29b-41d4-a716-446655440001")
        private val DELIVERY = UUID.fromString("880e8400-e29b-41d4-a716-446655440001")
    }
}
