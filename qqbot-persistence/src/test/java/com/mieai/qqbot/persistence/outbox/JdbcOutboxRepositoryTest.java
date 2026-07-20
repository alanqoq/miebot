package com.mieai.qqbot.persistence.outbox;

import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcOutboxRepositoryTest {
    private static final String BOT_ID = "550e8400-e29b-41d4-a716-446655440001";

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private OutboxRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = migratedDatabase(temporaryDirectory.resolve("outbox.db"));
        insertBot(dataSource, BOT_ID, "10001", BotEnvironment.SANDBOX);
        repository = new JdbcOutboxRepository(dataSource);
    }

    @Test
    void createsAndReadsPendingJobAndEnforcesOptionalDedupKey() {
        NewOutboxJob job = job("30000000-0000-0000-0000-000000000001", BASE_TIME, "reply:event-1");

        repository.create(job);

        OutboxJob stored = repository.findById(job.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stored.attempt()).isZero();
        assertThat(stored.fencingToken()).isZero();
        assertThat(stored.availableAt()).isEqualTo(BASE_TIME);
        assertThat(stored.leaseOwner()).isEmpty();
        assertThat(stored.leaseUntil()).isEmpty();
        assertThat(stored.completedAt()).isEmpty();
        assertThat(repository.findById(UUID.fromString("30000000-0000-0000-0000-000000000099"))).isEmpty();

        assertThatThrownBy(() -> repository.create(
                        job("30000000-0000-0000-0000-000000000002", BASE_TIME, "reply:event-1")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ownedClaimSkipsJobsForBotsLeasedByAnotherInstance() {
        String secondBot = "550e8400-e29b-41d4-a716-446655440002";
        insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX);
        repository.create(job("30100000-0000-0000-0000-000000000001", BOT_ID,
                BASE_TIME, "other-owner"));
        NewOutboxJob owned = job("30100000-0000-0000-0000-000000000002", secondBot,
                BASE_TIME, "owned");
        repository.create(owned);
        new JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(secondBot), 0, "instance-b",
                BASE_TIME, Duration.ofSeconds(30)).orElseThrow();

        OutboxJob claimed = repository.claimNextOwned("outbox-worker", "instance-b", BASE_TIME,
                Duration.ofSeconds(10)).orElseThrow();

        assertThat(claimed.id()).isEqualTo(owned.id());
        assertThat(repository.findById(UUID.fromString("30100000-0000-0000-0000-000000000001"))
                .orElseThrow().status()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void ownedClaimIgnoresALeaseForTheBotsPreviousShard() {
        NewOutboxJob job = job("30100000-0000-0000-0000-000000000003", BASE_TIME,
                "stale-shard");
        repository.create(job);
        new JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(BOT_ID), 0, "instance-b",
                BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        new JdbcTemplate(dataSource).update(
                "UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT_ID);

        assertThat(repository.claimNextOwned("outbox-worker", "instance-b", BASE_TIME,
                Duration.ofSeconds(10))).isEmpty();
        assertThat(repository.findById(job.id()).orElseThrow().status())
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void queriesNewestFirstWithOpaqueCursorAndDoesNotLoadFullPayload() {
        NewOutboxJob oldest = job(
                "30000000-0000-0000-0000-000000000011",
                BASE_TIME,
                "query:oldest");
        NewOutboxJob newest = new NewOutboxJob(
                UUID.fromString("30000000-0000-0000-0000-000000000012"),
                BotEnvironment.SANDBOX,
                BotId.parse(BOT_ID),
                Optional.empty(),
                "SEND_MESSAGE",
                Optional.of("query:newest"),
                "  {\"content\":\"a very large payload\"}",
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(1));
        repository.create(oldest);
        repository.create(newest);

        OutboxPage firstPage = repository.query(new OutboxQuery(
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        assertThat(firstPage.jobs()).extracting(OutboxJob::id)
                .containsExactly(newest.id());
        assertThat(firstPage.jobs().getFirst().payload()).isEqualTo("_");
        assertThat(firstPage.nextCursor()).isPresent();

        OutboxPage secondPage = repository.query(new OutboxQuery(
                1,
                firstPage.nextCursor(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
        assertThat(secondPage.jobs()).extracting(OutboxJob::id)
                .containsExactly(oldest.id());
        assertThat(secondPage.nextCursor()).isEmpty();
    }

    @Test
    void searchesOperationalFieldsAndReportsStatusCounts() {
        NewOutboxJob pending = job(
                "30000000-0000-0000-0000-000000000021", BASE_TIME, "search:pending");
        NewOutboxJob retry = job(
                "30000000-0000-0000-0000-000000000022", BASE_TIME, "search:retry");
        repository.create(pending);
        repository.create(retry);

        OutboxJob claimed = repository.claimNext(
                "worker-search", BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        repository.markRetry(
                claimed.id(),
                claimed.fencingToken(),
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(20),
                "remote timeout");

        OutboxPage page = repository.query(new OutboxQuery(
                10,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(OutboxStatus.RETRY_WAIT),
                Optional.empty(),
                Optional.of("timeout")));
        assertThat(page.jobs()).extracting(OutboxJob::id).containsExactly(claimed.id());

        OutboxQueueStats stats = repository.statistics();
        assertThat(stats.totalCount()).isEqualTo(2);
        assertThat(stats.pendingCount()).isEqualTo(1);
        assertThat(stats.retryWaitCount()).isEqualTo(1);
        assertThat(stats.inProgressCount()).isZero();
    }

    @Test
    void searchesByJobAndBotIdentifiers() {
        NewOutboxJob job = job(
                "30000000-0000-0000-0000-000000000031", BASE_TIME, "identifier-search");
        repository.create(job);

        OutboxPage byJobId = repository.query(search("000000000031"));
        OutboxPage byBotId = repository.query(search("446655440001"));

        assertThat(byJobId.jobs()).extracting(OutboxJob::id).containsExactly(job.id());
        assertThat(byBotId.jobs()).extracting(OutboxJob::id).containsExactly(job.id());
    }

    @Test
    void rejectsMalformedOutboxCursorBeforeQueryingDatabase() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.query(new OutboxQuery(
                        10,
                        Optional.of("not-a-cursor"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())))
                .withMessage("cursor is invalid");
    }

    @Test
    void claimsAndMarksAJobSucceeded() {
        NewOutboxJob job = job("31000000-0000-0000-0000-000000000001", BASE_TIME, "reply:success");
        repository.create(job);

        OutboxJob claimed = repository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30)).orElseThrow();

        assertThat(claimed.id()).isEqualTo(job.id());
        assertThat(claimed.status()).isEqualTo(OutboxStatus.IN_PROGRESS);
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(claimed.fencingToken()).isEqualTo(1);
        assertThat(claimed.leaseOwner()).contains("worker-1");
        assertThat(claimed.leaseUntil()).contains(BASE_TIME.plusSeconds(30));

        Instant completedAt = BASE_TIME.plusSeconds(1);
        repository.markSucceeded(job.id(), claimed.fencingToken(), completedAt);

        OutboxJob succeeded = repository.findById(job.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(OutboxStatus.SUCCEEDED);
        assertThat(succeeded.completedAt()).contains(completedAt);
        assertThat(succeeded.leaseOwner()).isEmpty();
        assertThat(succeeded.lastError()).isEmpty();
        assertThat(repository.claimNext("worker-2", completedAt, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void reclaimsAnExpiredLeaseAndFencesTheCrashedWorker() {
        NewOutboxJob job = job("32000000-0000-0000-0000-000000000001", BASE_TIME, "reply:crash");
        repository.create(job);
        OutboxJob firstClaim = repository.claimNext("crashed-worker", BASE_TIME, Duration.ofSeconds(10))
                .orElseThrow();

        assertThat(repository.claimNext(
                "replacement-worker", BASE_TIME.plusSeconds(9), Duration.ofSeconds(20))).isEmpty();
        OutboxJob reclaimed = repository.claimNext(
                        "replacement-worker", BASE_TIME.plusSeconds(10), Duration.ofSeconds(20))
                .orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(job.id());
        assertThat(reclaimed.attempt()).isEqualTo(2);
        assertThat(reclaimed.fencingToken()).isEqualTo(2);
        assertThat(reclaimed.leaseOwner()).contains("replacement-worker");
        assertThatThrownBy(() -> repository.markSucceeded(
                        job.id(), firstClaim.fencingToken(), BASE_TIME.plusSeconds(11)))
                .isInstanceOf(OutboxTransitionException.class);

        repository.markSucceeded(job.id(), reclaimed.fencingToken(), BASE_TIME.plusSeconds(11));
        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(OutboxStatus.SUCCEEDED);
    }

    @Test
    void waitsForRetryBackoffBeforeClaimingAgain() {
        NewOutboxJob job = job("33000000-0000-0000-0000-000000000001", BASE_TIME, "reply:retry");
        repository.create(job);
        OutboxJob firstClaim = repository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        Instant failureTime = BASE_TIME.plusSeconds(1);
        Instant retryAt = BASE_TIME.plusSeconds(60);

        repository.markRetry(job.id(), firstClaim.fencingToken(), failureTime, retryAt, "QQ API rate limited");

        OutboxJob waiting = repository.findById(job.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(OutboxStatus.RETRY_WAIT);
        assertThat(waiting.attempt()).isEqualTo(1);
        assertThat(waiting.availableAt()).isEqualTo(retryAt);
        assertThat(waiting.lastError()).contains("QQ API rate limited");
        assertThat(repository.claimNext(
                "worker-2", retryAt.minusNanos(1), Duration.ofSeconds(30))).isEmpty();

        OutboxJob secondClaim = repository.claimNext("worker-2", retryAt, Duration.ofSeconds(30)).orElseThrow();
        assertThat(secondClaim.attempt()).isEqualTo(2);
        assertThat(secondClaim.fencingToken()).isEqualTo(2);
        assertThat(secondClaim.lastError()).contains("QQ API rate limited");

        assertThatIllegalArgumentException().isThrownBy(() -> repository.markRetry(
                job.id(), secondClaim.fencingToken(), retryAt.plusSeconds(1), retryAt, "invalid backoff"));
    }

    @Test
    void recordsResultUnknownAndDeadLetterAsDistinctTerminalStates() {
        NewOutboxJob unknownJob = job(
                "34000000-0000-0000-0000-000000000001", BASE_TIME, "reply:unknown");
        NewOutboxJob deadJob = job(
                "34000000-0000-0000-0000-000000000002", BASE_TIME, "reply:dead");
        repository.create(unknownJob);
        repository.create(deadJob);

        OutboxJob unknownClaim = repository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        repository.markResultUnknown(
                unknownClaim.id(), unknownClaim.fencingToken(), BASE_TIME.plusSeconds(1), "request timed out after send");
        OutboxJob deadClaim = repository.claimNext(
                "worker-1", BASE_TIME.plusSeconds(1), Duration.ofSeconds(30)).orElseThrow();
        repository.markDeadLetter(
                deadClaim.id(), deadClaim.fencingToken(), BASE_TIME.plusSeconds(2), "retry policy exhausted");

        OutboxJob unknown = repository.findById(unknownJob.id()).orElseThrow();
        OutboxJob deadLetter = repository.findById(deadJob.id()).orElseThrow();
        assertThat(unknown.status()).isEqualTo(OutboxStatus.RESULT_UNKNOWN);
        assertThat(unknown.lastError()).contains("request timed out after send");
        assertThat(unknown.completedAt()).contains(BASE_TIME.plusSeconds(1));
        assertThat(deadLetter.status()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(deadLetter.lastError()).contains("retry policy exhausted");
        assertThat(deadLetter.completedAt()).contains(BASE_TIME.plusSeconds(2));
        assertThat(repository.claimNext(
                "worker-2", BASE_TIME.plusSeconds(3), Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void databaseRejectsIllegalStatusTransitions() throws Exception {
        NewOutboxJob job = job("35000000-0000-0000-0000-000000000001", BASE_TIME, "reply:invalid");
        repository.create(job);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE outbox_jobs
                            SET status = 'SUCCEEDED',
                                completed_at = '2026-07-16T12:00:01.000000000Z'
                            WHERE id = '35000000-0000-0000-0000-000000000001'
                            """))
                    .hasMessageContaining("invalid outbox status transition");
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE outbox_jobs
                            SET status = 'NOT_A_STATUS'
                            WHERE id = '35000000-0000-0000-0000-000000000001'
                            """))
                    .hasMessageContaining("invalid outbox status transition");
        }
        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @Timeout(15)
    void concurrentWorkersClaimOneJobExactlyOnce() throws Exception {
        NewOutboxJob job = job("36000000-0000-0000-0000-000000000001", BASE_TIME, "reply:race");
        repository.create(job);
        OutboxRepository firstRepository = new JdbcOutboxRepository(dataSource);
        OutboxRepository secondRepository = new JdbcOutboxRepository(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<OutboxJob>> first = executor.submit(() -> {
                start.await();
                return firstRepository.claimNext("worker-1", BASE_TIME, Duration.ofSeconds(30));
            });
            Future<Optional<OutboxJob>> second = executor.submit(() -> {
                start.await();
                return secondRepository.claimNext("worker-2", BASE_TIME, Duration.ofSeconds(30));
            });
            start.countDown();

            List<Optional<OutboxJob>> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            OutboxJob claimed = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(claimed.id()).isEqualTo(job.id());
            assertThat(claimed.attempt()).isEqualTo(1);
            assertThat(claimed.fencingToken()).isEqualTo(1);
            assertThat(repository.findById(job.id()).orElseThrow()).isEqualTo(claimed);
        } finally {
            executor.shutdownNow();
        }
    }

    private static NewOutboxJob job(String id, Instant availableAt, String dedupKey) {
        return job(id, BOT_ID, availableAt, dedupKey);
    }

    private static NewOutboxJob job(String id, String botId, Instant availableAt, String dedupKey) {
        return new NewOutboxJob(
                UUID.fromString(id),
                BotEnvironment.SANDBOX,
                BotId.parse(botId),
                Optional.empty(),
                "SEND_MESSAGE",
                Optional.of(dedupKey),
                "{\"content\":\"hello\"}",
                availableAt,
                BASE_TIME);
    }

    private static OutboxQuery search(String value) {
        return new OutboxQuery(
                10,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(value));
    }
}
