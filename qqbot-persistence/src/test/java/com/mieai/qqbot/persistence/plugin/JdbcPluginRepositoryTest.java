package com.mieai.qqbot.persistence.plugin;

import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.inbox.IncomingEvent;
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository;
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPluginRepositoryTest {
    private static final String BOT = "550e8400-e29b-41d4-a716-446655440001";
    private static final UUID EVENT = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
    private static final UUID BINDING = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
    private static final UUID DELIVERY = UUID.fromString("880e8400-e29b-41d4-a716-446655440001");

    @TempDir Path temporaryDirectory;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = migratedDatabase(temporaryDirectory.resolve("plugins.db"));
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX);
    }

    @Test
    void persistsBindingRevisionAndFencedDeliveryLifecycle() {
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        var bindings = new JdbcBotPluginBindingRepository(dataSource);
        var deliveries = new JdbcPluginDeliveryRepository(dataSource);
        artifacts.upsert(new PluginArtifact("echo", "Echo Reply", "1.0.0", "1.0.0", "echo.jar",
                "abc", "factory", "LOADED", true, BASE_TIME, BASE_TIME));

        BotPluginBinding binding = new BotPluginBinding(BINDING, "echo", BotId.parse(BOT), "{}", true, 0,
                BASE_TIME, BASE_TIME);
        bindings.insert(binding);
        assertThat(bindings.findByPluginAndBot("echo", BotId.parse(BOT))).contains(binding);
        BotPluginBinding updated = bindings.update(new BotPluginBinding(BINDING, "echo", BotId.parse(BOT),
                "{\"mode\":\"safe\"}", false, 0, BASE_TIME, BASE_TIME.plusSeconds(1)), 0);
        assertThat(updated.revision()).isEqualTo(1);
        assertThat(updated.enabled()).isFalse();

        new JdbcEventInboxRepository(dataSource).insertOrGet(new IncomingEvent(EVENT, BotEnvironment.SANDBOX,
                BotId.parse(BOT), "MESSAGE_CREATE", "event-1", "{}", BASE_TIME));

        assertThat(deliveries.createIfAbsent(DELIVERY, EVENT, BINDING, "default", BASE_TIME)).isTrue();
        assertThat(deliveries.createIfAbsent(UUID.randomUUID(), EVENT, BINDING, "default", BASE_TIME)).isFalse();
        assertThat(deliveries.claimNext("worker", BASE_TIME, Duration.ofSeconds(30))).isEmpty();
        assertThat(deliveries.pauseForBinding(BINDING, BASE_TIME.plusSeconds(1), "binding disabled"))
                .isEqualTo(1);
        assertThat(deliveries.findById(DELIVERY).orElseThrow().status())
                .isEqualTo(PluginDeliveryStatus.PAUSED);
        bindings.update(new BotPluginBinding(BINDING, "echo", BotId.parse(BOT), "{}", true, 1,
                BASE_TIME, BASE_TIME.plusSeconds(2)), 1);
        assertThat(deliveries.resumeForBinding(BINDING, BASE_TIME.plusSeconds(2))).isEqualTo(1);
        PluginDelivery claimed = deliveries.claimNext("worker", BASE_TIME.plusSeconds(3), Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimed.attempt()).isEqualTo(1);
        deliveries.markSucceeded(claimed.id(), claimed.fencingToken(), BASE_TIME.plusSeconds(4));
        assertThat(deliveries.claimNext("worker-2", BASE_TIME.plusSeconds(5), Duration.ofSeconds(30))).isEmpty();
        assertThat(deliveries.findById(DELIVERY)).get()
                .extracting(PluginDelivery::status).isEqualTo(PluginDeliveryStatus.SUCCEEDED);
        assertThat(deliveries.query(new PluginDeliveryQuery(
                10, Optional.empty(), Optional.of(BINDING), Optional.of(PluginDeliveryStatus.SUCCEEDED),
                Optional.of("default"))).deliveries()).hasSize(1);
        assertThat(deliveries.statistics()).isEqualTo(
                new PluginDeliveryQueueStats(1, 0, 0, 0, 1, 0, 0));
    }

    @Test
    void ownedClaimSelectsOnlyDeliveriesForTheInstanceBotLease() {
        String secondBot = "550e8400-e29b-41d4-a716-446655440002";
        UUID secondEvent = UUID.fromString("660e8400-e29b-41d4-a716-446655440002");
        UUID secondBinding = UUID.fromString("770e8400-e29b-41d4-a716-446655440002");
        UUID secondDelivery = UUID.fromString("880e8400-e29b-41d4-a716-446655440002");
        insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX);
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        var bindings = new JdbcBotPluginBindingRepository(dataSource);
        var inbox = new JdbcEventInboxRepository(dataSource);
        var deliveries = new JdbcPluginDeliveryRepository(dataSource);
        artifacts.upsert(new PluginArtifact("echo", "Echo Reply", "1.0.0", "2.0.0", "echo.jar",
                "abc", "factory", "LOADED", true, BASE_TIME, BASE_TIME));
        bindings.insert(new BotPluginBinding(BINDING, "echo", BotId.parse(BOT), "{}", true, 0,
                BASE_TIME, BASE_TIME));
        bindings.insert(new BotPluginBinding(secondBinding, "echo", BotId.parse(secondBot), "{}", true, 0,
                BASE_TIME, BASE_TIME));
        inbox.insertOrGet(new IncomingEvent(EVENT, BotEnvironment.SANDBOX, BotId.parse(BOT),
                "MESSAGE_CREATE", "event-other", "{}", BASE_TIME));
        inbox.insertOrGet(new IncomingEvent(secondEvent, BotEnvironment.SANDBOX, BotId.parse(secondBot),
                "MESSAGE_CREATE", "event-owned", "{}", BASE_TIME));
        deliveries.createIfAbsent(DELIVERY, EVENT, BINDING, "default", BASE_TIME);
        deliveries.createIfAbsent(secondDelivery, secondEvent, secondBinding, "default", BASE_TIME);
        new JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(secondBot), 0, "instance-b",
                BASE_TIME, Duration.ofSeconds(30)).orElseThrow();

        PluginDelivery claimed = deliveries.claimNextOwned("plugin-worker", "instance-b", BASE_TIME,
                Duration.ofSeconds(10)).orElseThrow();

        assertThat(claimed.id()).isEqualTo(secondDelivery);
        assertThat(deliveries.findById(DELIVERY).orElseThrow().status())
                .isEqualTo(PluginDeliveryStatus.PENDING);
    }

    @Test
    void ownedClaimIgnoresALeaseForTheBotsPreviousShard() {
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        var bindings = new JdbcBotPluginBindingRepository(dataSource);
        var inbox = new JdbcEventInboxRepository(dataSource);
        var deliveries = new JdbcPluginDeliveryRepository(dataSource);
        artifacts.upsert(new PluginArtifact("echo", "Echo Reply", "1.0.0", "2.0.0", "echo.jar",
                "abc", "factory", "LOADED", true, BASE_TIME, BASE_TIME));
        bindings.insert(new BotPluginBinding(BINDING, "echo", BotId.parse(BOT), "{}", true, 0,
                BASE_TIME, BASE_TIME));
        inbox.insertOrGet(new IncomingEvent(EVENT, BotEnvironment.SANDBOX, BotId.parse(BOT),
                "MESSAGE_CREATE", "event-stale-shard", "{}", BASE_TIME));
        deliveries.createIfAbsent(DELIVERY, EVENT, BINDING, "default", BASE_TIME);
        new JdbcBotLeaseRepository(dataSource).acquire(BotId.parse(BOT), 0, "instance-b",
                BASE_TIME, Duration.ofSeconds(30)).orElseThrow();
        new JdbcTemplate(dataSource).update(
                "UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT);

        assertThat(deliveries.claimNextOwned("plugin-worker", "instance-b", BASE_TIME,
                Duration.ofSeconds(10))).isEmpty();
        assertThat(deliveries.findById(DELIVERY).orElseThrow().status())
                .isEqualTo(PluginDeliveryStatus.PENDING);
    }
}
