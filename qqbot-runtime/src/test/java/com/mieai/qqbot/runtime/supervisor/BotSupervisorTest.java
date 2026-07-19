package com.mieai.qqbot.runtime.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.gateway.GatewayDispatch;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.OptimisticLockException;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.InboxInsertResult;
import com.mieai.qqbot.persistence.inbox.InboxPage;
import com.mieai.qqbot.persistence.inbox.InboxQuery;
import com.mieai.qqbot.persistence.inbox.InboxStatus;
import com.mieai.qqbot.persistence.inbox.IncomingEvent;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BotSupervisorTest {
    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");
    private static final Duration LONG_INTERVAL = Duration.ofDays(1);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);

    private final List<BotSupervisor> supervisors = new ArrayList<>();

    @AfterEach
    void closeSupervisors() {
        supervisors.forEach(BotSupervisor::close);
    }

    @Test
    void startsOnlyEnabledBotsAndPublishesSessionDiagnostics() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot enabled = bot(1, true, 1, 512L, "secret-a");
        StoredBot disabled = bot(2, false, 1, 512L, "secret-b");
        repository.put(enabled);
        repository.put(disabled);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);

        await(supervisor.start());
        drain(supervisor);

        assertThat(factory.created(enabled.id())).hasSize(1);
        assertThat(factory.created(disabled.id())).isEmpty();
        FakeRuntime runtime = factory.latest(enabled.id());
        runtime.ready("session-a", 7L);
        runtime.heartbeat(8L);
        runtime.dispatch(9L);
        drain(supervisor);

        BotRuntimeStatus enabledStatus = supervisor.status(enabled.id()).orElseThrow();
        assertThat(enabledStatus.state()).isEqualTo(BotRuntimeState.ONLINE);
        assertThat(enabledStatus.session()).contains(new BotSessionSnapshot("session-a", 9L));
        assertThat(enabledStatus.lastHeartbeatAt()).contains(NOW);
        assertThat(enabledStatus.lastDispatchAt()).contains(NOW);
        assertThat(supervisor.status(disabled.id()).orElseThrow().state())
                .isEqualTo(BotRuntimeState.DISABLED);
        assertThat(supervisor.summary())
                .extracting(
                        BotRuntimeSummary::configuredBots,
                        BotRuntimeSummary::enabledBots,
                        BotRuntimeSummary::runningBots,
                        BotRuntimeSummary::onlineBots)
                .containsExactly(2, 1, 1, 1);
    }

    @Test
    void committedConfigurationStartsPromptlyAndDisableFencesSynchronously() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        StoredBot enabled = bot(1, true, 1, 512L, "secret-a");

        repository.put(enabled);
        supervisor.onCommitted(change(enabled, BotConfigurationChangeKind.CREATED));
        drain(supervisor);

        FakeRuntime runtime = factory.latest(enabled.id());
        assertThat(runtime.started()).isTrue();

        StoredBot disabled = withConfiguration(enabled, false, 2, 512L, "secret-a");
        repository.put(disabled);
        supervisor.onCommitted(change(disabled, BotConfigurationChangeKind.DISABLED));

        assertThat(runtime.stopped()).isTrue();
        assertThat(supervisor.status(enabled.id()).orElseThrow().state())
                .isEqualTo(BotRuntimeState.DISABLED);
        drain(supervisor);
        assertThat(factory.created(enabled.id())).hasSize(1);
    }

    @Test
    void configurationRevisionRebuildsRuntimeAndRejectsOldGenerationCallbacks() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot original = bot(1, true, 1, 512L, "secret-a");
        repository.put(original);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime first = factory.latest(original.id());
        first.ready("old-session", 10L);
        drain(supervisor);

        StoredBot updated = withConfiguration(original, true, 2, 1024L, "secret-b");
        repository.put(updated);
        supervisor.onCommitted(change(updated, BotConfigurationChangeKind.UPDATED));
        drain(supervisor);

        FakeRuntime second = factory.latest(original.id());
        assertThat(first.stopped()).isTrue();
        assertThat(second).isNotSameAs(first);
        first.ready("stale-session", 99L);
        first.fail(new BotRuntimeFailure("STALE", "stale runtime failure", false));
        second.ready("current-session", 20L);
        drain(supervisor);

        BotRuntimeStatus status = supervisor.status(original.id()).orElseThrow();
        assertThat(status.configurationRevision()).isEqualTo(BotRevision.of(2));
        assertThat(status.session()).contains(new BotSessionSnapshot("current-session", 20L));
        assertThat(status.lastFailure()).isEmpty();
    }

    @Test
    void committedDisablePreventsAStaleReadFromInstallingItsRuntime()
            throws InterruptedException {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        StoredBot enabled = bot(1, true, 1, 512L, "secret-a");
        repository.put(enabled);
        BlockingRead blockingRead = repository.blockNextRead(enabled.id());

        CompletionStage<Void> staleReconciliation = supervisor.reconcile(enabled.id());
        assertThat(blockingRead.started().await(1L, TimeUnit.SECONDS)).isTrue();
        StoredBot disabled = withConfiguration(enabled, false, 2, 512L, "secret-a");
        repository.put(disabled);
        supervisor.onCommitted(change(disabled, BotConfigurationChangeKind.DISABLED));
        blockingRead.release().countDown();

        await(staleReconciliation);
        drain(supervisor);
        assertThat(factory.attempts(enabled.id())).isZero();
        assertThat(supervisor.status(enabled.id()).orElseThrow().state())
                .isEqualTo(BotRuntimeState.DISABLED);
    }

    @Test
    void exceptionalInitialStartKeepsRuntimeThatAlreadyEnteredBackoff() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot configured = bot(1, true, 1, 512L, "secret-a");
        repository.put(configured);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.failInitialStart(configured.id());
        BotSupervisor supervisor = supervisor(repository, factory);

        await(supervisor.start());
        drain(supervisor);

        FakeRuntime runtime = factory.latest(configured.id());
        assertThat(runtime.stopped()).isFalse();
        assertThat(supervisor.status(configured.id()).orElseThrow().state())
                .isEqualTo(BotRuntimeState.RECONNECTING);
        await(supervisor.reconcile(configured.id()));
        assertThat(factory.created(configured.id())).containsExactly(runtime);

        runtime.ready("recovered-session", 1L);
        drain(supervisor);
        assertThat(supervisor.status(configured.id()).orElseThrow().state())
                .isEqualTo(BotRuntimeState.ONLINE);
    }

    @Test
    void genericStartFailureDoesNotOverwriteSpecificRuntimeFailure() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot configured = bot(1, true, 1, 512L, "secret-a");
        repository.put(configured);
        BotRuntimeFailure specific = new BotRuntimeFailure(
                "SESSION_LIMITED", "QQ did not allow another Gateway session", true);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.failInitialStart(configured.id(), specific);
        BotSupervisor supervisor = supervisor(repository, factory);

        await(supervisor.start());
        drain(supervisor);

        BotRuntimeStatus status = supervisor.status(configured.id()).orElseThrow();
        assertThat(status.state()).isEqualTo(BotRuntimeState.FAILED);
        assertThat(status.lastFailure()).contains(specific);
    }

    @Test
    void oneFactoryFailureDoesNotPreventOtherBotsAndDoesNotHotLoopRevision() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot broken = bot(1, true, 1, 512L, "secret-a");
        StoredBot healthy = bot(2, true, 1, 512L, "secret-b");
        repository.put(broken);
        repository.put(healthy);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.failCreation(broken.id());
        BotSupervisor supervisor = supervisor(repository, factory);

        await(supervisor.start());
        drain(supervisor);

        BotRuntimeStatus brokenStatus = supervisor.status(broken.id()).orElseThrow();
        assertThat(brokenStatus.state()).isEqualTo(BotRuntimeState.FAILED);
        assertThat(brokenStatus.lastFailure().orElseThrow().code())
                .isEqualTo("RUNTIME_CONFIGURATION_FAILED");
        assertThat(factory.latest(healthy.id()).started()).isTrue();
        await(supervisor.reconcileAll());
        assertThat(factory.attempts(broken.id())).isEqualTo(1);
    }

    @Test
    void metadataOnlyRevisionDoesNotRestartRuntime() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot original = bot(1, true, 1, 512L, "secret-a");
        repository.put(original);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime runtime = factory.latest(original.id());

        BotDefinition definition = original.definition();
        StoredBot renamed = new StoredBot(
                new BotDefinition(
                        definition.id(),
                        "Renamed Bot",
                        definition.appId(),
                        definition.environment(),
                        definition.intents(),
                        definition.shardSpec(),
                        true,
                        BotRevision.of(2),
                        definition.createdAt(),
                        definition.updatedAt().plusSeconds(1L)),
                original.appSecret());
        repository.put(renamed);
        supervisor.onCommitted(change(renamed, BotConfigurationChangeKind.UPDATED));
        drain(supervisor);

        assertThat(factory.created(original.id())).containsExactly(runtime);
        assertThat(runtime.stopped()).isFalse();
        assertThat(supervisor.status(original.id()).orElseThrow().configurationRevision())
                .isEqualTo(BotRevision.of(2));
    }

    @Test
    void activeDatabaseChangeImmediatelyFencesOldEpochAndLoadsNewBots() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot oldBot = bot(1, true, 1, 512L, "secret-a");
        StoredBot newBot = bot(2, true, 1, 512L, "secret-b");
        repository.put(oldBot);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime oldRuntime = factory.latest(oldBot.id());

        repository.replaceAll(List.of(newBot));
        CompletionStage<Void> changed = supervisor.activeDatabaseChanged();

        assertThat(oldRuntime.stopped()).isTrue();
        await(changed);
        drain(supervisor);
        oldRuntime.ready("stale-database-session", 50L);
        drain(supervisor);

        assertThat(supervisor.status(oldBot.id())).isEmpty();
        assertThat(factory.latest(newBot.id()).started()).isTrue();
        assertThat(supervisor.status(newBot.id())).isPresent();
    }

    @Test
    void databaseTransitionGateRejectsDispatchAndDefersReconciliationUntilActivation() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot oldBot = bot(1, true, 1, 512L, "secret-a");
        StoredBot newBot = bot(2, true, 1, 512L, "secret-b");
        repository.put(oldBot);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        RecordingInboxRepository inbox = new RecordingInboxRepository();
        BotSupervisor supervisor = supervisor(repository, factory, inbox);

        await(supervisor.start());
        drain(supervisor);
        FakeRuntime oldRuntime = factory.latest(oldBot.id());
        oldRuntime.ready("old-session", 1L);
        drain(supervisor);

        supervisor.beforeActiveDatabaseChange();
        assertThat(oldRuntime.stopped()).isTrue();
        assertThat(oldRuntime.dispatch(new GatewayDispatch(
                        2L,
                        "C2C_MESSAGE_CREATE",
                        "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"during-swap\",\"d\":{}}")))
                .isFalse();
        assertThat(inbox.events()).isEmpty();

        repository.replaceAll(List.of(newBot));
        await(supervisor.reconcileAll());
        assertThat(factory.created(oldBot.id())).hasSize(1);
        assertThat(factory.created(newBot.id())).isEmpty();

        await(supervisor.activeDatabaseChanged());
        drain(supervisor);
        FakeRuntime replacement = factory.latest(newBot.id());
        replacement.ready("new-session", 1L);
        drain(supervisor);
        assertThat(replacement.dispatch(new GatewayDispatch(
                        2L,
                        "C2C_MESSAGE_CREATE",
                        "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"after-swap\",\"d\":{}}")))
                .isTrue();
        assertThat(inbox.events()).singleElement()
                .extracting(InboxEvent::platformEventId)
                .isEqualTo("after-swap");
    }

    @Test
    void persistsCompleteDispatchAndDeduplicatesItsPlatformEventId() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot configured = bot(1, true, 1, 512L, "secret-a");
        repository.put(configured);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        RecordingInboxRepository inbox = new RecordingInboxRepository();
        BotSupervisor supervisor = supervisor(repository, factory, inbox);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime runtime = factory.latest(configured.id());
        runtime.ready("session-inbox", 1L);
        drain(supervisor);
        String firstRaw = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\","
                + "\"id\":\"event-top\",\"d\":{\"id\":\"message-inner\",\"content\":\"hi\"}}";
        String replayRaw = "{\"op\":0,\"s\":3,\"t\":\"C2C_MESSAGE_CREATE\","
                + "\"id\":\"event-top\",\"d\":{\"id\":\"message-inner\",\"content\":\"replay\"}}";

        assertThat(runtime.dispatch(new GatewayDispatch(2L, "C2C_MESSAGE_CREATE", firstRaw)))
                .isTrue();
        assertThat(runtime.dispatch(new GatewayDispatch(3L, "C2C_MESSAGE_CREATE", replayRaw)))
                .isTrue();
        drain(supervisor);

        assertThat(inbox.events()).singleElement().satisfies(event -> {
            assertThat(event.environment()).isEqualTo(BotEnvironment.PRODUCTION);
            assertThat(event.botId()).isEqualTo(configured.id());
            assertThat(event.eventType()).isEqualTo("C2C_MESSAGE_CREATE");
            assertThat(event.platformEventId()).isEqualTo("event-top");
            assertThat(event.payload()).isEqualTo(firstRaw);
            assertThat(event.receivedAt()).isEqualTo(NOW);
        });
        assertThat(supervisor.status(configured.id()).orElseThrow().session())
                .contains(new BotSessionSnapshot("session-inbox", 3L));
    }

    @Test
    void rejectsDispatchAndPublishesFailureWhenInboxWriteFails() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot configured = bot(1, true, 1, 512L, "secret-a");
        repository.put(configured);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        RecordingInboxRepository inbox = new RecordingInboxRepository();
        inbox.failWrites();
        BotSupervisor supervisor = supervisor(repository, factory, inbox);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime runtime = factory.latest(configured.id());
        runtime.ready("session-failure", 1L);
        drain(supervisor);

        boolean accepted = runtime.dispatch(new GatewayDispatch(
                2L,
                "GROUP_AT_MESSAGE_CREATE",
                "{\"op\":0,\"s\":2,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"id\":\"event-fail\",\"d\":{}}"));

        assertThat(accepted).isFalse();
        BotRuntimeStatus status = supervisor.status(configured.id()).orElseThrow();
        assertThat(status.lastFailure()).isPresent();
        assertThat(status.lastFailure().orElseThrow().code()).isEqualTo("INBOX_PERSISTENCE_FAILED");
        assertThat(status.lastDispatchAt()).isEmpty();
        assertThat(status.session()).contains(new BotSessionSnapshot("session-failure", 1L));
        assertThat(inbox.events()).isEmpty();
    }

    @Test
    void staleGenerationAndDatabaseEpochCallbacksNeverWriteInbox() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot original = bot(1, true, 1, 512L, "secret-a");
        repository.put(original);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        RecordingInboxRepository inbox = new RecordingInboxRepository();
        BotSupervisor supervisor = supervisor(repository, factory, inbox);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime first = factory.latest(original.id());

        StoredBot updated = withConfiguration(original, true, 2, 1024L, "secret-b");
        repository.put(updated);
        supervisor.onCommitted(change(updated, BotConfigurationChangeKind.UPDATED));
        drain(supervisor);
        FakeRuntime second = factory.latest(original.id());

        assertThat(first.dispatch(new GatewayDispatch(
                        2L,
                        "C2C_MESSAGE_CREATE",
                        "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"stale-generation\",\"d\":{}}")))
                .isFalse();
        supervisor.beforeActiveDatabaseChange();
        assertThat(second.dispatch(new GatewayDispatch(
                        3L,
                        "C2C_MESSAGE_CREATE",
                        "{\"op\":0,\"s\":3,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"stale-database\",\"d\":{}}")))
                .isFalse();

        assertThat(inbox.events()).isEmpty();
    }

    @Test
    void environmentRevisionWritesNewRuntimeEventsToTheNewEnvironment() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot original = bot(1, true, 1, 512L, "secret-a");
        repository.put(original);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        RecordingInboxRepository inbox = new RecordingInboxRepository();
        BotSupervisor supervisor = supervisor(repository, factory, inbox);
        await(supervisor.start());
        drain(supervisor);

        StoredBot sandbox = withEnvironment(original, BotEnvironment.SANDBOX, 2L);
        repository.put(sandbox);
        supervisor.onCommitted(change(sandbox, BotConfigurationChangeKind.UPDATED));
        drain(supervisor);
        FakeRuntime replacement = factory.latest(original.id());
        replacement.ready("session-sandbox", 1L);
        drain(supervisor);

        assertThat(replacement.dispatch(new GatewayDispatch(
                        2L,
                        "C2C_MESSAGE_CREATE",
                        "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"sandbox-event\",\"d\":{}}")))
                .isTrue();

        assertThat(inbox.events()).singleElement()
                .extracting(InboxEvent::environment)
                .isEqualTo(BotEnvironment.SANDBOX);
    }

    @Test
    void periodicFullReconciliationFindsChangesWithoutNotification() throws InterruptedException {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(
                repository, factory, Duration.ofMillis(20));
        StoredBot configured = bot(1, true, 1, 512L, "secret-a");
        CountDownLatch created = factory.createdLatch(configured.id());

        await(supervisor.start());
        repository.put(configured);

        assertThat(created.await(2L, TimeUnit.SECONDS)).isTrue();
        assertThat(factory.latest(configured.id()).started()).isTrue();
    }

    @Test
    void closeFencesEveryRuntimeAndIgnoresLaterCallbacks() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot first = bot(1, true, 1, 512L, "secret-a");
        StoredBot second = bot(2, true, 1, 512L, "secret-b");
        repository.put(first);
        repository.put(second);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        drain(supervisor);

        FakeRuntime firstRuntime = factory.latest(first.id());
        FakeRuntime secondRuntime = factory.latest(second.id());
        supervisor.close();
        firstRuntime.ready("late-session", 1L);

        assertThat(firstRuntime.stopped()).isTrue();
        assertThat(secondRuntime.stopped()).isTrue();
        assertThat(supervisor.status(first.id())).isEmpty();
        assertThat(supervisor.status(second.id())).isEmpty();
    }

    @Test
    void gracefulShutdownWaitsForTransportCleanupAfterSynchronousFencing() {
        InMemoryBotRepository repository = new InMemoryBotRepository();
        StoredBot configured = bot(1, true, 1, 512L, "secret-a");
        repository.put(configured);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        BotSupervisor supervisor = supervisor(repository, factory);
        await(supervisor.start());
        drain(supervisor);
        FakeRuntime runtime = factory.latest(configured.id());
        runtime.deferStop();

        CompletionStage<Void> shutdown = supervisor.shutdown();

        assertThat(runtime.stopped()).isTrue();
        assertThat(shutdown.toCompletableFuture()).isNotDone();
        runtime.completeStop();
        await(shutdown);
        assertThat(shutdown.toCompletableFuture()).isCompleted();
    }

    private BotSupervisor supervisor(
            InMemoryBotRepository repository, FakeRuntimeFactory factory) {
        return supervisor(repository, factory, LONG_INTERVAL);
    }

    private BotSupervisor supervisor(
            InMemoryBotRepository repository,
            FakeRuntimeFactory factory,
            EventInboxRepository inboxRepository) {
        BotSupervisor supervisor = new BotSupervisor(
                repository,
                factory,
                LONG_INTERVAL,
                SHUTDOWN_TIMEOUT,
                Clock.fixed(NOW, ZoneOffset.UTC),
                inboxRepository);
        supervisors.add(supervisor);
        return supervisor;
    }

    private BotSupervisor supervisor(
            InMemoryBotRepository repository,
            FakeRuntimeFactory factory,
            Duration reconciliationInterval) {
        BotSupervisor supervisor = new BotSupervisor(
                repository,
                factory,
                reconciliationInterval,
                SHUTDOWN_TIMEOUT,
                Clock.fixed(NOW, ZoneOffset.UTC));
        supervisors.add(supervisor);
        return supervisor;
    }

    private static void drain(BotSupervisor supervisor) {
        await(supervisor.start());
    }

    private static void await(CompletionStage<Void> stage) {
        stage.toCompletableFuture().join();
    }

    private static BotConfigurationChange change(
            StoredBot bot, BotConfigurationChangeKind kind) {
        BotDefinition definition = bot.definition();
        return new BotConfigurationChange(
                definition.id(), definition.revision(), definition.enabled(), kind);
    }

    private static StoredBot bot(
            long id, boolean enabled, long revision, long intents, String encryptedSecret) {
        BotId botId = BotId.of(new UUID(0L, id));
        Instant createdAt = NOW.minusSeconds(60L);
        return new StoredBot(
                new BotDefinition(
                        botId,
                        "Bot " + id,
                        QqAppId.of("1905208" + id),
                        BotEnvironment.PRODUCTION,
                        GatewayIntents.of(intents),
                        ShardSpec.single(),
                        enabled,
                        BotRevision.of(revision),
                        createdAt,
                        createdAt.plusSeconds(revision)),
                SecretCiphertext.of("v1.nonce." + encryptedSecret, "primary"));
    }

    private static StoredBot withConfiguration(
            StoredBot original,
            boolean enabled,
            long revision,
            long intents,
            String encryptedSecret) {
        BotDefinition current = original.definition();
        return new StoredBot(
                new BotDefinition(
                        current.id(),
                        current.displayName(),
                        current.appId(),
                        current.environment(),
                        GatewayIntents.of(intents),
                        current.shardSpec(),
                        enabled,
                        BotRevision.of(revision),
                        current.createdAt(),
                        current.updatedAt().plusSeconds(1L)),
                SecretCiphertext.of("v1.nonce." + encryptedSecret, "primary"));
    }

    private static StoredBot withEnvironment(
            StoredBot original, BotEnvironment environment, long revision) {
        BotDefinition current = original.definition();
        return new StoredBot(
                new BotDefinition(
                        current.id(),
                        current.displayName(),
                        current.appId(),
                        environment,
                        current.intents(),
                        current.shardSpec(),
                        current.enabled(),
                        BotRevision.of(revision),
                        current.createdAt(),
                        current.updatedAt().plusSeconds(1L)),
                original.appSecret());
    }

    private static final class FakeRuntimeFactory implements BotRuntimeFactory {
        private final ConcurrentMap<BotId, List<FakeRuntime>> runtimes = new ConcurrentHashMap<>();
        private final ConcurrentMap<BotId, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final ConcurrentMap<BotId, CountDownLatch> createdLatches = new ConcurrentHashMap<>();
        private final Map<BotId, Boolean> creationFailures = new ConcurrentHashMap<>();
        private final Map<BotId, Boolean> startFailures = new ConcurrentHashMap<>();
        private final Map<BotId, BotRuntimeFailure> specificStartFailures =
                new ConcurrentHashMap<>();

        @Override
        public ManagedBotRuntime create(StoredBot configuration, BotRuntimeObserver observer) {
            BotId botId = configuration.id();
            attempts.computeIfAbsent(botId, ignored -> new AtomicInteger()).incrementAndGet();
            if (creationFailures.containsKey(botId)) {
                throw new IllegalStateException("factory failure");
            }
            FakeRuntime runtime = new FakeRuntime(
                    observer,
                    startFailures.containsKey(botId),
                    specificStartFailures.get(botId));
            runtimes.compute(botId, (ignored, existing) -> {
                List<FakeRuntime> updated = existing == null
                        ? new ArrayList<>()
                        : new ArrayList<>(existing);
                updated.add(runtime);
                return List.copyOf(updated);
            });
            CountDownLatch latch = createdLatches.get(botId);
            if (latch != null) {
                latch.countDown();
            }
            return runtime;
        }

        void failCreation(BotId botId) {
            creationFailures.put(botId, Boolean.TRUE);
        }

        void failInitialStart(BotId botId) {
            startFailures.put(botId, Boolean.TRUE);
        }

        void failInitialStart(BotId botId, BotRuntimeFailure failure) {
            specificStartFailures.put(botId, failure);
        }

        CountDownLatch createdLatch(BotId botId) {
            CountDownLatch latch = new CountDownLatch(1);
            createdLatches.put(botId, latch);
            return latch;
        }

        int attempts(BotId botId) {
            AtomicInteger value = attempts.get(botId);
            return value == null ? 0 : value.get();
        }

        List<FakeRuntime> created(BotId botId) {
            return runtimes.getOrDefault(botId, List.of());
        }

        FakeRuntime latest(BotId botId) {
            List<FakeRuntime> created = created(botId);
            if (created.isEmpty()) {
                throw new AssertionError("No runtime created for " + botId);
            }
            return created.getLast();
        }
    }

    private static final class FakeRuntime implements ManagedBotRuntime {
        private final BotRuntimeObserver observer;
        private final boolean exceptionalInitialStart;
        private final BotRuntimeFailure specificStartFailure;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile CompletableFuture<Void> stopResult =
                CompletableFuture.completedFuture(null);

        private FakeRuntime(
                BotRuntimeObserver observer,
                boolean exceptionalInitialStart,
                BotRuntimeFailure specificStartFailure) {
            this.observer = observer;
            this.exceptionalInitialStart = exceptionalInitialStart;
            this.specificStartFailure = specificStartFailure;
        }

        @Override
        public CompletionStage<Void> start() {
            started.set(true);
            if (specificStartFailure != null) {
                observer.onFailure(specificStartFailure);
                return CompletableFuture.failedFuture(
                        new IllegalStateException("specific initial start failure"));
            }
            observer.onStateChanged(BotRuntimeState.CONNECTING);
            if (exceptionalInitialStart) {
                observer.onReconnectScheduled();
                return CompletableFuture.failedFuture(
                        new IllegalStateException("initial connect failed"));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop() {
            stopped.set(true);
            return stopResult;
        }

        boolean started() {
            return started.get();
        }

        boolean stopped() {
            return stopped.get();
        }

        void ready(String sessionId, long sequence) {
            observer.onReady(new BotSessionSnapshot(sessionId, sequence));
        }

        void heartbeat(long sequence) {
            observer.onHeartbeatAcknowledged(sequence);
        }

        void dispatch(long sequence) {
            observer.onDispatch(sequence);
        }

        boolean dispatch(GatewayDispatch dispatch) {
            boolean accepted = observer.acceptDispatch(dispatch);
            if (accepted) {
                observer.onDispatch(dispatch);
            }
            return accepted;
        }

        void fail(BotRuntimeFailure failure) {
            observer.onFailure(failure);
        }

        void deferStop() {
            stopResult = new CompletableFuture<>();
        }

        void completeStop() {
            stopResult.complete(null);
        }
    }

    private static final class InMemoryBotRepository implements BotRepository {
        private final ConcurrentMap<BotId, StoredBot> bots = new ConcurrentHashMap<>();
        private volatile BlockingRead blockingRead;

        @Override
        public Optional<StoredBot> findById(BotId id) {
            StoredBot result = bots.get(id);
            BlockingRead blocking = blockingRead;
            if (blocking != null
                    && blocking.botId().equals(id)
                    && blocking.claimed().compareAndSet(false, true)) {
                blocking.started().countDown();
                try {
                    if (!blocking.release().await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release repository read");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("repository read interrupted", exception);
                } finally {
                    blockingRead = null;
                }
            }
            return Optional.ofNullable(result);
        }

        @Override
        public List<StoredBot> findAll() {
            return bots.values().stream()
                    .sorted(Comparator.comparing(bot -> bot.id().toString()))
                    .toList();
        }

        @Override
        public List<StoredBot> findEnabled() {
            return findAll().stream()
                    .filter(bot -> bot.definition().enabled())
                    .toList();
        }

        @Override
        public void insert(StoredBot bot) {
            if (bots.putIfAbsent(bot.id(), bot) != null) {
                throw new IllegalStateException("duplicate bot");
            }
        }

        @Override
        public BotRevision update(StoredBot bot, BotRevision expectedRevision) {
            AtomicReferenceResult result = new AtomicReferenceResult();
            bots.compute(bot.id(), (ignored, current) -> {
                if (current == null
                        || !current.definition().revision().equals(expectedRevision)) {
                    result.failed = true;
                    return current;
                }
                BotDefinition desired = bot.definition();
                BotRevision next = expectedRevision.next();
                result.revision = next;
                return new StoredBot(
                        new BotDefinition(
                                desired.id(),
                                desired.displayName(),
                                desired.appId(),
                                desired.environment(),
                                desired.intents(),
                                desired.shardSpec(),
                                desired.enabled(),
                                next,
                                desired.createdAt(),
                                desired.updatedAt()),
                        bot.appSecret());
            });
            if (result.failed) {
                throw new OptimisticLockException(bot.id(), expectedRevision);
            }
            return result.revision;
        }

        void put(StoredBot bot) {
            bots.put(bot.id(), bot);
        }

        void replaceAll(List<StoredBot> replacements) {
            bots.clear();
            replacements.forEach(this::put);
        }

        BlockingRead blockNextRead(BotId botId) {
            BlockingRead blocking = new BlockingRead(
                    botId,
                    new AtomicBoolean(),
                    new CountDownLatch(1),
                    new CountDownLatch(1));
            blockingRead = blocking;
            return blocking;
        }

        private static final class AtomicReferenceResult {
            private boolean failed;
            private BotRevision revision;
        }
    }

    private static final class RecordingInboxRepository implements EventInboxRepository {
        private final Map<InboxKey, InboxEvent> byKey = new java.util.LinkedHashMap<>();
        private final Map<UUID, InboxEvent> byId = new java.util.LinkedHashMap<>();
        private boolean failWrites;

        @Override
        public synchronized InboxInsertResult insertOrGet(IncomingEvent event) {
            if (failWrites) {
                throw new IllegalStateException("inbox unavailable");
            }
            InboxKey key = new InboxKey(
                    event.environment(),
                    event.botId(),
                    event.eventType(),
                    event.platformEventId());
            InboxEvent existing = byKey.get(key);
            if (existing != null) {
                return new InboxInsertResult(false, existing);
            }
            InboxEvent stored = new InboxEvent(
                    event.id(),
                    event.environment(),
                    event.botId(),
                    event.eventType(),
                    event.platformEventId(),
                    event.payload(),
                    InboxStatus.RECEIVED,
                    0,
                    event.receivedAt(),
                    Optional.empty(),
                    Optional.empty(),
                    0L,
                    Optional.empty(),
                    event.receivedAt(),
                    event.receivedAt());
            byKey.put(key, stored);
            byId.put(stored.id(), stored);
            return new InboxInsertResult(true, stored);
        }

        @Override
        public synchronized Optional<InboxEvent> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public synchronized InboxPage query(InboxQuery query) {
            List<InboxEvent> events = byId.values().stream()
                    .limit(query.limit())
                    .toList();
            return new InboxPage(events, Optional.empty());
        }

        synchronized List<InboxEvent> events() {
            return List.copyOf(byId.values());
        }

        synchronized void failWrites() {
            failWrites = true;
        }

        private record InboxKey(
                BotEnvironment environment,
                BotId botId,
                String eventType,
                String platformEventId) {}
    }

    private record BlockingRead(
            BotId botId,
            AtomicBoolean claimed,
            CountDownLatch started,
            CountDownLatch release) {}
}
