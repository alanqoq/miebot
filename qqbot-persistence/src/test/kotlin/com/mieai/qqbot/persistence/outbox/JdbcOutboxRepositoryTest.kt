package com.mieai.qqbot.persistence.outbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifact
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class JdbcOutboxRepositoryTest {
    @TempDir lateinit var temporaryDirectory: Path
    private lateinit var dataSource: DataSource
    private lateinit var repository: OutboxRepository
    @BeforeEach fun setUp() { dataSource = migratedDatabase(temporaryDirectory.resolve("outbox.db")); insertBot(dataSource, BOT_ID, "10001", BotEnvironment.SANDBOX); repository = JdbcOutboxRepository(dataSource) }

    @Test fun createsAndReadsPendingJobAndEnforcesNullableDedupKey() {
        val job = job("30000000-0000-0000-0000-000000000001", BASE_TIME, "reply:event-1"); repository.create(job); val stored = requireNotNull(repository.findById(job.id))
        assertThat(stored.status).isEqualTo(OutboxStatus.PENDING); assertThat(stored.attempt).isZero(); assertThat(stored.fencingToken).isZero(); assertThat(stored.availableAt).isEqualTo(BASE_TIME); assertThat(stored.leaseOwner).isNull(); assertThat(stored.leaseUntil).isNull(); assertThat(stored.completedAt).isNull(); assertThat(repository.findById(UUID.fromString("30000000-0000-0000-0000-000000000099"))).isNull()
        assertThatThrownBy { repository.create(job("30000000-0000-0000-0000-000000000002", BASE_TIME, "reply:event-1")) }.isInstanceOf(DataAccessException::class.java)
    }
    @Test fun ownedClaimSkipsJobsForBotsLeasedByAnotherInstance() {
        val secondBot = "550e8400-e29b-41d4-a716-446655440002"; insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX); repository.create(job("30100000-0000-0000-0000-000000000001", BOT_ID, BASE_TIME, "other-owner")); val owned = job("30100000-0000-0000-0000-000000000002", secondBot, BASE_TIME, "owned"); repository.create(owned); requireNotNull(JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(secondBot), 0, "instance-b", BASE_TIME, Duration.ofSeconds(30)))
        val claimed = requireNotNull(repository.claimNextOwned("outbox-worker", "instance-b", BASE_TIME, Duration.ofSeconds(10))); assertThat(claimed.id).isEqualTo(owned.id); assertThat(requireNotNull(repository.findById(UUID.fromString("30100000-0000-0000-0000-000000000001"))).status).isEqualTo(OutboxStatus.PENDING)
    }
    @Test fun ownedClaimIgnoresALeaseForTheBotsPreviousShard() { val job = job("30100000-0000-0000-0000-000000000003", BASE_TIME, "stale-shard"); repository.create(job); requireNotNull(JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(BOT_ID), 0, "instance-b", BASE_TIME, Duration.ofSeconds(30))); JdbcTemplate(dataSource).update("UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT_ID); assertThat(repository.claimNextOwned("outbox-worker", "instance-b", BASE_TIME, Duration.ofSeconds(10))).isNull(); assertThat(requireNotNull(repository.findById(job.id)).status).isEqualTo(OutboxStatus.PENDING) }
    @Test fun queriesNewestFirstWithOpaqueCursorAndDoesNotLoadFullPayload() {
        val oldest = job("30000000-0000-0000-0000-000000000011", BASE_TIME, "query:oldest"); val newest = NewOutboxJob(UUID.fromString("30000000-0000-0000-0000-000000000012"), BotEnvironment.SANDBOX, BotId.parse(BOT_ID), null, "SEND_MESSAGE", "query:newest", "  {\"content\":\"a very large payload\"}", BASE_TIME.plusSeconds(1), BASE_TIME.plusSeconds(1), null); repository.create(oldest); repository.create(newest)
        val first = repository.query(query(1)); assertThat(first.jobs.map { it.id }).containsExactly(newest.id); assertThat(first.jobs.first().payload).isEqualTo("_"); assertThat(first.nextCursor).isNotNull(); val second = repository.query(query(1, first.nextCursor)); assertThat(second.jobs.map { it.id }).containsExactly(oldest.id); assertThat(second.nextCursor).isNull()
    }
    @Test fun searchesOperationalFieldsAndReportsStatusCounts() {
        val pending = job("30000000-0000-0000-0000-000000000021", BASE_TIME, "search:pending"); val retry = job("30000000-0000-0000-0000-000000000022", BASE_TIME, "search:retry"); repository.create(pending); repository.create(retry); val claimed = requireNotNull(repository.claimNext("worker-search", BASE_TIME, Duration.ofSeconds(30))); repository.markRetry(claimed.id, claimed.fencingToken, BASE_TIME.plusSeconds(1), BASE_TIME.plusSeconds(20), "remote timeout")
        val page = repository.query(OutboxQuery(10, null, null, null, OutboxStatus.RETRY_WAIT, null, "timeout")); assertThat(page.jobs.map { it.id }).containsExactly(claimed.id); val stats = repository.statistics(); assertThat(stats.totalCount).isEqualTo(2); assertThat(stats.pendingCount).isEqualTo(1); assertThat(stats.retryWaitCount).isEqualTo(1); assertThat(stats.inProgressCount).isZero()
    }
    @Test fun searchesByJobAndBotIdentifiers() { val job = job("30000000-0000-0000-0000-000000000031", BASE_TIME, "identifier-search"); repository.create(job); assertThat(repository.query(search("000000000031")).jobs.map { it.id }).containsExactly(job.id); assertThat(repository.query(search("446655440001")).jobs.map { it.id }).containsExactly(job.id) }
    @Test fun rejectsMalformedOutboxCursorBeforeQueryingDatabase() { assertThatIllegalArgumentException().isThrownBy { repository.query(OutboxQuery(10, "not-a-cursor", null, null, null, null, null)) }.withMessage("cursor is invalid") }
    @Test fun claimsAndMarksAJobSucceeded() {
        val bindingId = UUID.fromString("41000000-0000-0000-0000-000000000001"); insertBinding(bindingId); val job = NewOutboxJob(UUID.fromString("31000000-0000-0000-0000-000000000001"), BotEnvironment.SANDBOX, BotId.parse(BOT_ID), null, "SEND_MESSAGE", "reply:success", "{\"content\":\"hello\"}", BASE_TIME, BASE_TIME, bindingId); repository.create(job)
        val claimed = requireNotNull(repository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30))); assertThat(claimed.id).isEqualTo(job.id); assertThat(claimed.status).isEqualTo(OutboxStatus.IN_PROGRESS); assertThat(claimed.attempt).isEqualTo(1); assertThat(claimed.fencingToken).isEqualTo(1); assertThat(claimed.leaseOwner).isEqualTo("worker-1"); assertThat(claimed.leaseUntil).isEqualTo(BASE_TIME.plusSeconds(30))
        val completedAt = BASE_TIME.plusSeconds(1); repository.markSucceeded(job.id, claimed.fencingToken, completedAt, receipt("qq-message-1")); val succeeded = requireNotNull(repository.findById(job.id)); assertThat(succeeded.status).isEqualTo(OutboxStatus.SUCCEEDED); assertThat(succeeded.completedAt).isEqualTo(completedAt); assertThat(succeeded.leaseOwner).isNull(); assertThat(succeeded.lastError).isNull(); assertThat(succeeded.producerBindingId).isEqualTo(bindingId); assertThat(succeeded.platformMessageId).isEqualTo("qq-message-1"); assertThat(succeeded.platformMessageSequence).isEqualTo(7); assertThat(succeeded.platformTimestamp).isEqualTo("2026-07-24T12:00:01Z"); assertThat(repository.findByIdAndProducerBindingId(job.id, bindingId)).isEqualTo(succeeded); assertThat(repository.findByIdAndProducerBindingId(job.id, UUID.randomUUID())).isNull(); assertThat(repository.query(search("qq-message-1")).jobs.map { it.id }).containsExactly(job.id); assertThat(repository.query(search(bindingId.toString())).jobs.map { it.id }).containsExactly(job.id); assertThat(repository.claimNext("worker-2", completedAt, Duration.ofSeconds(30))).isNull()
    }
    @Test fun reclaimsAnExpiredLeaseAndFencesTheCrashedWorker() {
        val job = job("32000000-0000-0000-0000-000000000001", BASE_TIME, "reply:crash"); repository.create(job); val first = requireNotNull(repository.claimNext("crashed-worker", BASE_TIME, Duration.ofSeconds(10))); assertThat(repository.claimNext("replacement-worker", BASE_TIME.plusSeconds(9), Duration.ofSeconds(20))).isNull(); val reclaimed = requireNotNull(repository.claimNext("replacement-worker", BASE_TIME.plusSeconds(10), Duration.ofSeconds(20))); assertThat(reclaimed.id).isEqualTo(job.id); assertThat(reclaimed.attempt).isEqualTo(2); assertThat(reclaimed.fencingToken).isEqualTo(2); assertThat(reclaimed.leaseOwner).isEqualTo("replacement-worker"); assertThatThrownBy { repository.markSucceeded(job.id, first.fencingToken, BASE_TIME.plusSeconds(11), receipt("stale-worker-message")) }.isInstanceOf(OutboxTransitionException::class.java); repository.markSucceeded(job.id, reclaimed.fencingToken, BASE_TIME.plusSeconds(11), receipt("replacement-worker-message")); assertThat(requireNotNull(repository.findById(job.id)).status).isEqualTo(OutboxStatus.SUCCEEDED)
    }
    @Test fun waitsForRetryBackoffBeforeClaimingAgain() { val job = job("33000000-0000-0000-0000-000000000001", BASE_TIME, "reply:retry"); repository.create(job); val first = requireNotNull(repository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30))); val failureTime = BASE_TIME.plusSeconds(1); val retryAt = BASE_TIME.plusSeconds(60); repository.markRetry(job.id, first.fencingToken, failureTime, retryAt, "QQ API rate limited"); val waiting = requireNotNull(repository.findById(job.id)); assertThat(waiting.status).isEqualTo(OutboxStatus.RETRY_WAIT); assertThat(waiting.attempt).isEqualTo(1); assertThat(waiting.availableAt).isEqualTo(retryAt); assertThat(waiting.lastError).isEqualTo("QQ API rate limited"); assertThat(repository.claimNext("worker-2", retryAt.minusNanos(1), Duration.ofSeconds(30))).isNull(); val second = requireNotNull(repository.claimNext("worker-2", retryAt, Duration.ofSeconds(30))); assertThat(second.attempt).isEqualTo(2); assertThat(second.fencingToken).isEqualTo(2); assertThat(second.lastError).isEqualTo("QQ API rate limited"); assertThatIllegalArgumentException().isThrownBy { repository.markRetry(job.id, second.fencingToken, retryAt.plusSeconds(1), retryAt, "invalid backoff") } }
    @Test fun recordsResultUnknownAndDeadLetterAsDistinctTerminalStates() {
        val unknownJob = job("34000000-0000-0000-0000-000000000001", BASE_TIME, "reply:unknown"); val deadJob = job("34000000-0000-0000-0000-000000000002", BASE_TIME, "reply:dead"); repository.create(unknownJob); repository.create(deadJob); val unknownClaim = requireNotNull(repository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30))); repository.markResultUnknown(unknownClaim.id, unknownClaim.fencingToken, BASE_TIME.plusSeconds(1), "request timed out after send"); val deadClaim = requireNotNull(repository.claimNext("worker-1", BASE_TIME.plusSeconds(1), Duration.ofSeconds(30))); repository.markDeadLetter(deadClaim.id, deadClaim.fencingToken, BASE_TIME.plusSeconds(2), "retry policy exhausted"); val unknown = requireNotNull(repository.findById(unknownJob.id)); val dead = requireNotNull(repository.findById(deadJob.id)); assertThat(unknown.status).isEqualTo(OutboxStatus.RESULT_UNKNOWN); assertThat(unknown.lastError).isEqualTo("request timed out after send"); assertThat(unknown.completedAt).isEqualTo(BASE_TIME.plusSeconds(1)); assertThat(unknown.platformMessageId).isNull(); assertThat(unknown.platformMessageSequence).isNull(); assertThat(unknown.platformTimestamp).isNull(); assertThat(dead.status).isEqualTo(OutboxStatus.DEAD_LETTER); assertThat(dead.lastError).isEqualTo("retry policy exhausted"); assertThat(dead.completedAt).isEqualTo(BASE_TIME.plusSeconds(2)); assertThat(repository.claimNext("worker-2", BASE_TIME.plusSeconds(3), Duration.ofSeconds(30))).isNull()
    }
    @Test fun databaseRejectsIllegalStatusTransitions() { val job = job("35000000-0000-0000-0000-000000000001", BASE_TIME, "reply:invalid"); repository.create(job); dataSource.connection.use { connection -> connection.createStatement().use { statement -> assertThatThrownBy { statement.executeUpdate("UPDATE outbox_jobs SET status = 'SUCCEEDED', completed_at = '2026-07-16T12:00:01.000000000Z' WHERE id = '35000000-0000-0000-0000-000000000001'") }.hasMessageContaining("invalid outbox status transition"); assertThatThrownBy { statement.executeUpdate("UPDATE outbox_jobs SET status = 'NOT_A_STATUS' WHERE id = '35000000-0000-0000-0000-000000000001'") }.hasMessageContaining("invalid outbox status transition") } }; assertThat(requireNotNull(repository.findById(job.id)).status).isEqualTo(OutboxStatus.PENDING) }
    @Test @Timeout(15) fun concurrentWorkersClaimOneJobExactlyOnce() { val job = job("36000000-0000-0000-0000-000000000001", BASE_TIME, "reply:race"); repository.create(job); val firstRepository = JdbcOutboxRepository(dataSource); val secondRepository = JdbcOutboxRepository(dataSource); val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2); try { val first = executor.submit<OutboxJob?> { start.await(); firstRepository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30)) }; val second = executor.submit<OutboxJob?> { start.await(); secondRepository.claimNext("worker-2", BASE_TIME, Duration.ofSeconds(30)) }; start.countDown(); val results = listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)); assertThat(results).filteredOn { it != null }.hasSize(1); val claimed = requireNotNull(results.first { it != null }); assertThat(claimed.id).isEqualTo(job.id); assertThat(claimed.attempt).isEqualTo(1); assertThat(claimed.fencingToken).isEqualTo(1); assertThat(requireNotNull(repository.findById(job.id))).isEqualTo(claimed) } finally { executor.shutdownNow() } }

    private fun job(id: String, availableAt: Instant, dedupKey: String) = job(id, BOT_ID, availableAt, dedupKey)
    private fun job(id: String, botId: String, availableAt: Instant, dedupKey: String) = NewOutboxJob(UUID.fromString(id), BotEnvironment.SANDBOX, BotId.parse(botId), null, "SEND_MESSAGE", dedupKey, "{\"content\":\"hello\"}", availableAt, BASE_TIME, null)
    private fun query(limit: Int, cursor: String? = null) = OutboxQuery(limit, cursor, null, null, null, null, null)
    private fun search(value: String) = OutboxQuery(10, null, null, null, null, null, value)
    private fun receipt(messageId: String) = OutboxSendReceipt(messageId, 7, "2026-07-24T12:00:01Z")
    private fun insertBinding(bindingId: UUID) { JdbcPluginArtifactRepository(dataSource).upsert(PluginArtifact("echo", "Echo Reply", "1.0.0", "1.0.0", "echo.jar", "sha256", "factory", "LOADED", true, BASE_TIME, BASE_TIME)); JdbcBotPluginBindingRepository(dataSource).insert(BotPluginBinding(bindingId, "echo", BotId.parse(BOT_ID), true, 0, BASE_TIME, BASE_TIME, PluginBindingRuntimeState.ACTIVE, null)) }
    companion object { private const val BOT_ID = "550e8400-e29b-41d4-a716-446655440001" }
}
