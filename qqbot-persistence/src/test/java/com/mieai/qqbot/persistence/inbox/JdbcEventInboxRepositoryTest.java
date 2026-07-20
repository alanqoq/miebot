package com.mieai.qqbot.persistence.inbox;

import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.sql.Connection;
import java.sql.Statement;
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
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcEventInboxRepositoryTest {
    private static final String BOT_ID = "550e8400-e29b-41d4-a716-446655440001";

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private EventInboxRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = migratedDatabase(temporaryDirectory.resolve("inbox.db"));
        insertBot(dataSource, BOT_ID, "10001", BotEnvironment.SANDBOX);
        repository = new JdbcEventInboxRepository(dataSource);
    }

    @Test
    void databaseRejectsAnUnknownInboxStatus() throws Exception {
        IncomingEvent event = event(
                "11000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-event-invalid", "{}");
        repository.insertOrGet(event);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                            UPDATE event_inbox
                            SET status = 'NOT_A_STATUS'
                            WHERE id = '11000000-0000-0000-0000-000000000001'
                            """))
                    .hasMessageContaining("CHECK constraint failed");
        }
        assertThat(repository.findById(event.id()).orElseThrow().status()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    void insertsOnceAndReturnsTheOriginalEventForADuplicatePlatformKey() {
        IncomingEvent original = event(
                "10000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-event-1", "{\"value\":1}");
        IncomingEvent duplicate = event(
                "10000000-0000-0000-0000-000000000002", "MESSAGE_CREATE", "platform-event-1", "{\"value\":2}");

        InboxInsertResult first = repository.insertOrGet(original);
        InboxInsertResult second = repository.insertOrGet(duplicate);

        assertThat(first.inserted()).isTrue();
        assertThat(first.event().id()).isEqualTo(original.id());
        assertThat(first.event().status()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(first.event().attempt()).isZero();
        assertThat(first.event().availableAt()).isEqualTo(BASE_TIME);
        assertThat(second.inserted()).isFalse();
        assertThat(second.event()).isEqualTo(first.event());
        assertThat(second.event().payload()).isEqualTo("{\"value\":1}");
        assertThat(repository.findById(original.id())).contains(first.event());
        assertThat(repository.findById(duplicate.id())).isEmpty();

        InboxInsertResult differentType = repository.insertOrGet(event(
                "10000000-0000-0000-0000-000000000003",
                "INTERACTION_CREATE",
                "platform-event-1",
                "{\"value\":3}"));
        assertThat(differentType.inserted()).isTrue();
    }

    @Test
    @Timeout(15)
    void concurrentDuplicateReceiversStillPersistExactlyOneEvent() throws Exception {
        IncomingEvent first = event(
                "20000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-event-2", "{\"source\":1}");
        IncomingEvent second = event(
                "20000000-0000-0000-0000-000000000002", "MESSAGE_CREATE", "platform-event-2", "{\"source\":2}");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InboxInsertResult> firstResult = executor.submit(() -> {
                start.await();
                return repository.insertOrGet(first);
            });
            Future<InboxInsertResult> secondResult = executor.submit(() -> {
                start.await();
                return repository.insertOrGet(second);
            });
            start.countDown();

            List<InboxInsertResult> results = List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(InboxInsertResult::inserted).hasSize(1);
            assertThat(results).extracting(result -> result.event().id()).containsOnly(
                    results.getFirst().event().id());
            assertThat(List.of(repository.findById(first.id()), repository.findById(second.id())))
                    .filteredOn(java.util.Optional::isPresent)
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exactLimitDoesNotAdvertiseAnEmptyFollowUpPage() {
        repository.insertOrGet(event(
                "30000000-0000-0000-0000-000000000001",
                "MESSAGE_CREATE",
                "platform-page-1",
                "{\"value\":1}"));
        repository.insertOrGet(event(
                "30000000-0000-0000-0000-000000000002",
                "MESSAGE_CREATE",
                "platform-page-2",
                "{\"value\":2}"));

        InboxPage page = repository.query(InboxQuery.firstPage(2));

        assertThat(page.events()).hasSize(2);
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void cursorReturnsEveryRowOnceWhenMoreThanTheLimitExists() {
        repository.insertOrGet(event(
                "40000000-0000-0000-0000-000000000001",
                "MESSAGE_CREATE",
                "platform-cursor-1",
                "{\"value\":1}"));
        repository.insertOrGet(event(
                "40000000-0000-0000-0000-000000000002",
                "MESSAGE_CREATE",
                "platform-cursor-2",
                "{\"value\":2}"));
        repository.insertOrGet(event(
                "40000000-0000-0000-0000-000000000003",
                "MESSAGE_CREATE",
                "platform-cursor-3",
                "{\"value\":3}"));

        InboxPage first = repository.query(InboxQuery.firstPage(2));
        InboxPage second = repository.query(new InboxQuery(
                2,
                first.nextCursor(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        assertThat(first.events()).hasSize(2);
        assertThat(first.nextCursor()).isPresent();
        assertThat(second.events()).hasSize(1);
        assertThat(second.nextCursor()).isEmpty();
        assertThat(java.util.stream.Stream.concat(
                        first.events().stream(), second.events().stream())
                .map(InboxEvent::platformEventId))
                .containsExactlyInAnyOrder(
                        "platform-cursor-1", "platform-cursor-2", "platform-cursor-3");
    }

    @Test
    void claimsAndFencesInboxProcessing() {
        IncomingEvent incoming = event(
                "50000000-0000-0000-0000-000000000001", "MESSAGE_CREATE", "platform-claim", "{}");
        InboxEvent stored = repository.insertOrGet(incoming).event();
        InboxEvent claimed = repository.claimNext("inbox-worker", BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(stored.id());
        assertThat(claimed.status()).isEqualTo(InboxStatus.PROCESSING);
        assertThat(claimed.attempt()).isEqualTo(1);
        repository.markDispatched(claimed.id(), claimed.fencingToken(), BASE_TIME.plusSeconds(1));
        assertThat(repository.findById(claimed.id()).orElseThrow().status()).isEqualTo(InboxStatus.DISPATCHED);
    }

    @Test
    void ownedClaimSkipsEventsForBotsLeasedByAnotherInstance() {
        String secondBot = "550e8400-e29b-41d4-a716-446655440002";
        insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX);
        repository.insertOrGet(event(BOT_ID, "51000000-0000-0000-0000-000000000001",
                "MESSAGE_CREATE", "platform-other-owner", "{}"));
        InboxEvent owned = repository.insertOrGet(event(secondBot,
                "51000000-0000-0000-0000-000000000002", "MESSAGE_CREATE",
                "platform-owned", "{}")).event();
        new JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(secondBot), 0, "instance-b",
                BASE_TIME, Duration.ofSeconds(30)).orElseThrow();

        InboxEvent claimed = repository.claimNextOwned("inbox-worker", "instance-b", BASE_TIME,
                Duration.ofSeconds(10)).orElseThrow();

        assertThat(claimed.id()).isEqualTo(owned.id());
        assertThat(repository.findById(UUID.fromString("51000000-0000-0000-0000-000000000001"))
                .orElseThrow().status()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    void ownedClaimIgnoresALeaseForTheBotsPreviousShard() {
        InboxEvent event = repository.insertOrGet(event(
                "51000000-0000-0000-0000-000000000003", "MESSAGE_CREATE",
                "platform-stale-shard", "{}")).event();
        new JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(BOT_ID), 0, "instance-b",
                BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        new JdbcTemplate(dataSource).update(
                "UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT_ID);

        assertThat(repository.claimNextOwned("inbox-worker", "instance-b", BASE_TIME,
                Duration.ofSeconds(10))).isEmpty();
        assertThat(repository.findById(event.id()).orElseThrow().status())
                .isEqualTo(InboxStatus.RECEIVED);
    }

    private static IncomingEvent event(String id, String eventType, String platformEventId, String payload) {
        return event(BOT_ID, id, eventType, platformEventId, payload);
    }

    private static IncomingEvent event(
            String botId, String id, String eventType, String platformEventId, String payload) {
        return new IncomingEvent(
                UUID.fromString(id),
                BotEnvironment.SANDBOX,
                BotId.parse(botId),
                eventType,
                platformEventId,
                payload,
                BASE_TIME);
    }
}
