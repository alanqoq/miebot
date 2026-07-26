package com.mieai.qqbot.persistence.inbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class JdbcEventInboxRepositoryTest {
    @TempDir lateinit var temporaryDirectory: Path
    private lateinit var dataSource: DataSource
    private lateinit var repository: EventInboxRepository

    @BeforeEach fun setUp() { dataSource = migratedDatabase(temporaryDirectory.resolve("inbox.db")); insertBot(dataSource, BOT_ID, "10001", BotEnvironment.SANDBOX); repository = JdbcEventInboxRepository(dataSource) }

    @Test fun databaseRejectsAnUnknownInboxStatus() {
        val event = event("11000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-event-invalid", "{}")
        repository.insertOrGet(event)
        dataSource.connection.use { connection -> connection.createStatement().use { statement -> assertThatThrownBy { statement.executeUpdate("UPDATE event_inbox SET status = 'NOT_A_STATUS' WHERE id = '11000000-0000-0000-0000-000000000001'") }.hasMessageContaining("CHECK constraint failed") } }
        assertThat(requireNotNull(repository.findById(event.id)).status).isEqualTo(InboxStatus.RECEIVED)
    }

    @Test fun insertsOnceAndReturnsTheOriginalEventForADuplicatePlatformKey() {
        val original = event("10000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-event-1", "{\"value\":1}")
        val duplicate = event("10000000-0000-0000-0000-000000000002", "MESSAGE_CREATE", "platform-event-1", "{\"value\":2}")
        val first = repository.insertOrGet(original); val second = repository.insertOrGet(duplicate)
        assertThat(first.inserted).isTrue(); assertThat(first.event.id).isEqualTo(original.id); assertThat(first.event.status).isEqualTo(InboxStatus.RECEIVED); assertThat(first.event.attempt).isZero(); assertThat(first.event.availableAt).isEqualTo(BASE_TIME)
        assertThat(second.inserted).isFalse(); assertThat(second.event).isEqualTo(first.event); assertThat(second.event.payload).isEqualTo("{\"value\":1}"); assertThat(repository.findById(original.id)).isEqualTo(first.event); assertThat(repository.findById(duplicate.id)).isNull()
        assertThat(repository.insertOrGet(event("10000000-0000-0000-0000-000000000003", "INTERACTION_CREATE", "platform-event-1", "{\"value\":3}")).inserted).isTrue()
    }

    @Test @Timeout(15) fun concurrentDuplicateReceiversStillPersistExactlyOneEvent() {
        val first = event("20000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-event-2", "{\"source\":1}")
        val second = event("20000000-0000-0000-0000-000000000002", "MESSAGE_CREATE", "platform-event-2", "{\"source\":2}")
        val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val firstResult = executor.submit<InboxInsertResult> { start.await(); repository.insertOrGet(first) }; val secondResult = executor.submit<InboxInsertResult> { start.await(); repository.insertOrGet(second) }; start.countDown()
            val results = listOf(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS))
            assertThat(results).filteredOn { it.inserted }.hasSize(1); assertThat(results.map { it.event.id }).containsOnly(results.first().event.id)
            assertThat(listOf(repository.findById(first.id), repository.findById(second.id))).filteredOn { it != null }.hasSize(1)
        } finally { executor.shutdownNow() }
    }

    @Test fun exactLimitDoesNotAdvertiseAnEmptyFollowUpPage() {
        repository.insertOrGet(event("30000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-page-1", "{\"value\":1}")); repository.insertOrGet(event("30000000-0000-0000-0000-000000000002", "MESSAGE_CREATE", "platform-page-2", "{\"value\":2}"))
        val page = repository.query(InboxQuery.firstPage(2)); assertThat(page.events).hasSize(2); assertThat(page.nextCursor).isNull()
    }

    @Test fun cursorReturnsEveryRowOnceWhenMoreThanTheLimitExists() {
        (1..3).forEach { repository.insertOrGet(event("40000000-0000-0000-0000-00000000000$it", "MESSAGE_CREATE", "platform-cursor-$it", "{\"value\":$it}")) }
        val first = repository.query(InboxQuery.firstPage(2)); val second = repository.query(InboxQuery(2, first.nextCursor, null, null, null, null, null))
        assertThat(first.events).hasSize(2); assertThat(first.nextCursor).isNotNull(); assertThat(second.events).hasSize(1); assertThat(second.nextCursor).isNull(); assertThat((first.events + second.events).map { it.platformEventId }).containsExactlyInAnyOrder("platform-cursor-1", "platform-cursor-2", "platform-cursor-3")
    }

    @Test fun claimsAndFencesInboxProcessing() {
        val incoming = event("50000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-claim", "{}"); val stored = repository.insertOrGet(incoming).event
        val claimed = requireNotNull(repository.claimNext("inbox-worker", BASE_TIME, Duration.ofSeconds(30))); assertThat(claimed.id).isEqualTo(stored.id); assertThat(claimed.status).isEqualTo(InboxStatus.PROCESSING); assertThat(claimed.attempt).isEqualTo(1)
        repository.markDispatched(claimed.id, claimed.fencingToken, BASE_TIME.plusSeconds(1)); assertThat(requireNotNull(repository.findById(claimed.id)).status).isEqualTo(InboxStatus.DISPATCHED)
    }

    @Test fun ownedClaimSkipsEventsForBotsLeasedByAnotherInstance() {
        val secondBot = "550e8400-e29b-41d4-a716-446655440002"; insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX)
        repository.insertOrGet(event(BOT_ID, "51000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-other-owner", "{}")); val owned = repository.insertOrGet(event(secondBot, "51000000-0000-0000-0000-000000000002", "MESSAGE_CREATE", "platform-owned", "{}")).event
        requireNotNull(JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(secondBot), 0, "instance-b", BASE_TIME, Duration.ofSeconds(30)))
        val claimed = requireNotNull(repository.claimNextOwned("inbox-worker", "instance-b", BASE_TIME, Duration.ofSeconds(10))); assertThat(claimed.id).isEqualTo(owned.id); assertThat(requireNotNull(repository.findById(UUID.fromString("51000000-0000-0000-0000-000000000001"))).status).isEqualTo(InboxStatus.RECEIVED)
    }

    @Test fun ownedClaimIgnoresALeaseForTheBotsPreviousShard() {
        val event = repository.insertOrGet(event("51000000-0000-0000-0000-000000000003", "MESSAGE_CREATE", "platform-stale-shard", "{}")).event
        requireNotNull(JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(BOT_ID), 0, "instance-b", BASE_TIME, Duration.ofSeconds(30))); JdbcTemplate(dataSource).update("UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT_ID)
        assertThat(repository.claimNextOwned("inbox-worker", "instance-b", BASE_TIME, Duration.ofSeconds(10))).isNull(); assertThat(requireNotNull(repository.findById(event.id)).status).isEqualTo(InboxStatus.RECEIVED)
    }

    private fun event(id: String, eventType: String, platformEventId: String, payload: String) = event(BOT_ID, id, eventType, platformEventId, payload)
    private fun event(botId: String, id: String, eventType: String, platformEventId: String, payload: String) = IncomingEvent(UUID.fromString(id), BotEnvironment.SANDBOX, BotId.parse(botId), eventType, platformEventId, payload, BASE_TIME)
    companion object { private const val BOT_ID = "550e8400-e29b-41d4-a716-446655440001" }
}
