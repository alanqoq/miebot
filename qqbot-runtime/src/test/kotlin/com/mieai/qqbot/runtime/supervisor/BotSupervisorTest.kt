package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.gateway.GatewayDispatch
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.OptimisticLockException
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.inbox.InboxInsertResult
import com.mieai.qqbot.persistence.inbox.InboxPage
import com.mieai.qqbot.persistence.inbox.InboxQuery
import com.mieai.qqbot.persistence.inbox.InboxStatus
import com.mieai.qqbot.persistence.inbox.IncomingEvent
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeKind
import com.mieai.qqbot.runtime.event.BotGatewayEvent
import com.mieai.qqbot.runtime.event.BotGatewayEventSink
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BotSupervisorTest {
    private val supervisors = mutableListOf<BotSupervisor>()

    @AfterEach
    fun closeSupervisors() {
        supervisors.forEach(BotSupervisor::close)
    }

    @Test
    fun startsOnlyEnabledBotsAndPublishesSessionDiagnostics() {
        val repository = InMemoryBotRepository()
        val enabled = bot(1, true, 1, 512L, "secret-a")
        val disabled = bot(2, false, 1, 512L, "secret-b")
        repository.put(enabled); repository.put(disabled)
        val factory = FakeRuntimeFactory()
        val supervisor = supervisor(repository, factory)
        await(supervisor.start()); drain(supervisor)
        assertThat(factory.created(enabled.id)).hasSize(1)
        assertThat(factory.created(disabled.id)).isEmpty()
        val runtime = factory.latest(enabled.id)
        runtime.ready("session-a", 7L); runtime.heartbeat(8L); runtime.dispatch(9L); drain(supervisor)
        val enabledStatus = requireNotNull(supervisor.status(enabled.id))
        assertThat(enabledStatus.state).isEqualTo(BotRuntimeState.ONLINE)
        assertThat(enabledStatus.session).isEqualTo(BotSessionSnapshot("session-a", 9L))
        assertThat(enabledStatus.lastHeartbeatAt).isEqualTo(NOW)
        assertThat(enabledStatus.lastDispatchAt).isEqualTo(NOW)
        assertThat(requireNotNull(supervisor.status(disabled.id)).state).isEqualTo(BotRuntimeState.DISABLED)
        assertThat(supervisor.summary()).extracting(
            BotRuntimeSummary::configuredBots, BotRuntimeSummary::enabledBots,
            BotRuntimeSummary::runningBots, BotRuntimeSummary::onlineBots,
        ).containsExactly(2, 1, 1, 1)
    }

    @Test
    fun committedConfigurationStartsPromptlyAndDisableFencesSynchronously() {
        val repository = InMemoryBotRepository(); val factory = FakeRuntimeFactory()
        val supervisor = supervisor(repository, factory); await(supervisor.start())
        val enabled = bot(1, true, 1, 512L, "secret-a")
        repository.put(enabled); supervisor.onCommitted(change(enabled, BotConfigurationChangeKind.CREATED)); drain(supervisor)
        val runtime = factory.latest(enabled.id)
        assertThat(runtime.started()).isTrue()
        val disabled = withConfiguration(enabled, false, 2, 512L, "secret-a")
        repository.put(disabled); supervisor.onCommitted(change(disabled, BotConfigurationChangeKind.DISABLED))
        assertThat(runtime.stopped()).isTrue()
        assertThat(requireNotNull(supervisor.status(enabled.id)).state).isEqualTo(BotRuntimeState.DISABLED)
        drain(supervisor); assertThat(factory.created(enabled.id)).hasSize(1)
    }

    @Test
    fun configurationRevisionRebuildsRuntimeAndRejectsOldGenerationCallbacks() {
        val repository = InMemoryBotRepository(); val original = bot(1, true, 1, 512L, "secret-a")
        repository.put(original); val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory)
        await(supervisor.start()); drain(supervisor); val first = factory.latest(original.id)
        first.ready("old-session", 10L); drain(supervisor)
        val updated = withConfiguration(original, true, 2, 1024L, "secret-b")
        repository.put(updated); supervisor.onCommitted(change(updated, BotConfigurationChangeKind.UPDATED)); drain(supervisor)
        val second = factory.latest(original.id)
        assertThat(first.stopped()).isTrue(); assertThat(second).isNotSameAs(first)
        first.ready("stale-session", 99L); first.fail(BotRuntimeFailure("STALE", "stale runtime failure", false))
        second.ready("current-session", 20L); drain(supervisor)
        val status = requireNotNull(supervisor.status(original.id))
        assertThat(status.configurationRevision).isEqualTo(BotRevision.of(2))
        assertThat(status.session).isEqualTo(BotSessionSnapshot("current-session", 20L))
        assertThat(status.lastFailure).isNull()
    }

    @Test
    fun committedDisablePreventsAStaleReadFromInstallingItsRuntime() {
        val repository = InMemoryBotRepository(); val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory)
        await(supervisor.start()); val enabled = bot(1, true, 1, 512L, "secret-a")
        repository.put(enabled); val blockingRead = repository.blockNextRead(enabled.id)
        val staleReconciliation = supervisor.reconcile(enabled.id)
        assertThat(blockingRead.started.await(1L, TimeUnit.SECONDS)).isTrue()
        val disabled = withConfiguration(enabled, false, 2, 512L, "secret-a")
        repository.put(disabled); supervisor.onCommitted(change(disabled, BotConfigurationChangeKind.DISABLED)); blockingRead.release.countDown()
        await(staleReconciliation); drain(supervisor)
        assertThat(factory.attempts(enabled.id)).isZero()
        assertThat(requireNotNull(supervisor.status(enabled.id)).state).isEqualTo(BotRuntimeState.DISABLED)
    }

    @Test
    fun exceptionalInitialStartKeepsRuntimeThatAlreadyEnteredBackoff() {
        val repository = InMemoryBotRepository(); val configured = bot(1, true, 1, 512L, "secret-a")
        repository.put(configured); val factory = FakeRuntimeFactory(); factory.failInitialStart(configured.id)
        val supervisor = supervisor(repository, factory); await(supervisor.start()); drain(supervisor)
        val runtime = factory.latest(configured.id)
        assertThat(runtime.stopped()).isFalse()
        assertThat(requireNotNull(supervisor.status(configured.id)).state).isEqualTo(BotRuntimeState.RECONNECTING)
        await(supervisor.reconcile(configured.id)); assertThat(factory.created(configured.id)).containsExactly(runtime)
        runtime.ready("recovered-session", 1L); drain(supervisor)
        assertThat(requireNotNull(supervisor.status(configured.id)).state).isEqualTo(BotRuntimeState.ONLINE)
    }

    @Test
    fun genericStartFailureDoesNotOverwriteSpecificRuntimeFailure() {
        val repository = InMemoryBotRepository(); val configured = bot(1, true, 1, 512L, "secret-a"); repository.put(configured)
        val specific = BotRuntimeFailure("SESSION_LIMITED", "QQ did not allow another Gateway session", true)
        val factory = FakeRuntimeFactory(); factory.failInitialStart(configured.id, specific)
        val supervisor = supervisor(repository, factory); await(supervisor.start()); drain(supervisor)
        val status = requireNotNull(supervisor.status(configured.id))
        assertThat(status.state).isEqualTo(BotRuntimeState.FAILED); assertThat(status.lastFailure).isEqualTo(specific)
    }

    @Test
    fun oneFactoryFailureDoesNotPreventOtherBotsAndDoesNotHotLoopRevision() {
        val repository = InMemoryBotRepository(); val broken = bot(1, true, 1, 512L, "secret-a"); val healthy = bot(2, true, 1, 512L, "secret-b")
        repository.put(broken); repository.put(healthy); val factory = FakeRuntimeFactory(); factory.failCreation(broken.id)
        val supervisor = supervisor(repository, factory); await(supervisor.start()); drain(supervisor)
        val brokenStatus = requireNotNull(supervisor.status(broken.id))
        assertThat(brokenStatus.state).isEqualTo(BotRuntimeState.FAILED)
        assertThat(requireNotNull(brokenStatus.lastFailure).code).isEqualTo("RUNTIME_CONFIGURATION_FAILED")
        assertThat(factory.latest(healthy.id).started()).isTrue(); await(supervisor.reconcileAll())
        assertThat(factory.attempts(broken.id)).isEqualTo(1)
    }

    @Test
    fun metadataOnlyRevisionDoesNotRestartRuntime() {
        val repository = InMemoryBotRepository(); val original = bot(1, true, 1, 512L, "secret-a"); repository.put(original)
        val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory); await(supervisor.start()); drain(supervisor)
        val runtime = factory.latest(original.id); val definition = original.definition
        val renamed = StoredBot(BotDefinition(definition.id, "Renamed Bot", definition.appId, definition.environment, definition.intents, definition.shardSpec, true, BotRevision.of(2), definition.createdAt, definition.updatedAt.plusSeconds(1L)), original.appSecret)
        repository.put(renamed); supervisor.onCommitted(change(renamed, BotConfigurationChangeKind.UPDATED)); drain(supervisor)
        assertThat(factory.created(original.id)).containsExactly(runtime); assertThat(runtime.stopped()).isFalse()
        assertThat(requireNotNull(supervisor.status(original.id)).configurationRevision).isEqualTo(BotRevision.of(2))
    }

    @Test
    fun activeDatabaseChangeImmediatelyFencesOldEpochAndLoadsNewBots() {
        val repository = InMemoryBotRepository(); val oldBot = bot(1, true, 1, 512L, "secret-a"); val newBot = bot(2, true, 1, 512L, "secret-b")
        repository.put(oldBot); val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory)
        await(supervisor.start()); drain(supervisor); val oldRuntime = factory.latest(oldBot.id)
        repository.replaceAll(listOf(newBot)); val changed = supervisor.activeDatabaseChanged()
        assertThat(oldRuntime.stopped()).isTrue(); await(changed); drain(supervisor); oldRuntime.ready("stale-database-session", 50L); drain(supervisor)
        assertThat(supervisor.status(oldBot.id)).isNull(); assertThat(factory.latest(newBot.id).started()).isTrue(); assertThat(supervisor.status(newBot.id)).isNotNull()
    }

    @Test
    fun databaseTransitionGateRejectsDispatchAndDefersReconciliationUntilActivation() {
        val repository = InMemoryBotRepository(); val oldBot = bot(1, true, 1, 512L, "secret-a"); val newBot = bot(2, true, 1, 512L, "secret-b")
        repository.put(oldBot); val factory = FakeRuntimeFactory(); val inbox = RecordingInboxRepository(); val supervisor = supervisor(repository, factory, inbox)
        await(supervisor.start()); drain(supervisor); val oldRuntime = factory.latest(oldBot.id); oldRuntime.ready("old-session", 1L); drain(supervisor)
        supervisor.beforeActiveDatabaseChange(); assertThat(oldRuntime.stopped()).isTrue()
        assertThat(oldRuntime.dispatch(GatewayDispatch(2L, "C2C_MESSAGE_CREATE", "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"during-swap\",\"d\":{}}"))).isFalse()
        assertThat(inbox.events()).isEmpty()
        repository.replaceAll(listOf(newBot)); await(supervisor.reconcileAll()); assertThat(factory.created(oldBot.id)).hasSize(1); assertThat(factory.created(newBot.id)).isEmpty()
        await(supervisor.activeDatabaseChanged()); drain(supervisor); val replacement = factory.latest(newBot.id); replacement.ready("new-session", 1L); drain(supervisor)
        assertThat(replacement.dispatch(GatewayDispatch(2L, "C2C_MESSAGE_CREATE", "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"after-swap\",\"d\":{}}"))).isTrue()
        assertThat(inbox.events()).singleElement().extracting(InboxEvent::platformEventId).isEqualTo("after-swap")
    }

    @Test
    fun persistsCompleteDispatchAndDeduplicatesItsPlatformEventId() {
        val repository = InMemoryBotRepository(); val configured = bot(1, true, 1, 512L, "secret-a"); repository.put(configured)
        val factory = FakeRuntimeFactory(); val inbox = RecordingInboxRepository(); val supervisor = supervisor(repository, factory, inbox)
        await(supervisor.start()); drain(supervisor); val runtime = factory.latest(configured.id); runtime.ready("session-inbox", 1L); drain(supervisor)
        val firstRaw = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event-top\",\"d\":{\"id\":\"message-inner\",\"content\":\"hi\"}}"
        val replayRaw = "{\"op\":0,\"s\":3,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event-top\",\"d\":{\"id\":\"message-inner\",\"content\":\"replay\"}}"
        assertThat(runtime.dispatch(GatewayDispatch(2L, "C2C_MESSAGE_CREATE", firstRaw))).isTrue(); assertThat(runtime.dispatch(GatewayDispatch(3L, "C2C_MESSAGE_CREATE", replayRaw))).isTrue(); drain(supervisor)
        val event = inbox.events().single()
        assertThat(event.environment).isEqualTo(BotEnvironment.PRODUCTION); assertThat(event.botId).isEqualTo(configured.id)
        assertThat(event.eventType).isEqualTo("C2C_MESSAGE_CREATE"); assertThat(event.platformEventId).isEqualTo("event-top")
        assertThat(event.payload).isEqualTo(firstRaw); assertThat(event.receivedAt).isEqualTo(NOW)
        assertThat(requireNotNull(supervisor.status(configured.id)).session).isEqualTo(BotSessionSnapshot("session-inbox", 3L))
    }

    @Test
    fun publishesOnlyNewDurableDispatchesToReadOnlySubscribers() {
        val repository = InMemoryBotRepository(); val configured = bot(1, true, 1, 512L, "secret-a"); repository.put(configured)
        val factory = FakeRuntimeFactory(); val inbox = RecordingInboxRepository(); val published = mutableListOf<BotGatewayEvent>()
        val supervisor = supervisor(repository, factory, inbox, BotGatewayEventSink { published.add(it) }); await(supervisor.start()); drain(supervisor)
        val runtime = factory.latest(configured.id); val dispatch = GatewayDispatch(2L, "C2C_MESSAGE_CREATE", "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event\",\"d\":{}}")
        assertThat(runtime.dispatch(dispatch)).isTrue(); assertThat(runtime.dispatch(dispatch)).isTrue()
        val event = published.single()
        assertThat(event.botId).isEqualTo(configured.id); assertThat(event.dispatch).isEqualTo(dispatch); assertThat(event.receivedAt).isEqualTo(NOW)
    }

    @Test
    fun rejectsDispatchAndPublishesFailureWhenInboxWriteFails() {
        val repository = InMemoryBotRepository(); val configured = bot(1, true, 1, 512L, "secret-a"); repository.put(configured)
        val factory = FakeRuntimeFactory(); val inbox = RecordingInboxRepository(); inbox.failWrites(); val supervisor = supervisor(repository, factory, inbox)
        await(supervisor.start()); drain(supervisor); val runtime = factory.latest(configured.id); runtime.ready("session-failure", 1L); drain(supervisor)
        val accepted = runtime.dispatch(GatewayDispatch(2L, "GROUP_AT_MESSAGE_CREATE", "{\"op\":0,\"s\":2,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"id\":\"event-fail\",\"d\":{}}"))
        assertThat(accepted).isFalse(); val status = requireNotNull(supervisor.status(configured.id))
        assertThat(status.lastFailure).isNotNull(); assertThat(requireNotNull(status.lastFailure).code).isEqualTo("INBOX_PERSISTENCE_FAILED")
        assertThat(status.lastDispatchAt).isNull(); assertThat(status.session).isEqualTo(BotSessionSnapshot("session-failure", 1L)); assertThat(inbox.events()).isEmpty()
    }

    @Test
    fun staleGenerationAndDatabaseEpochCallbacksNeverWriteInbox() {
        val repository = InMemoryBotRepository(); val original = bot(1, true, 1, 512L, "secret-a"); repository.put(original)
        val factory = FakeRuntimeFactory(); val inbox = RecordingInboxRepository(); val supervisor = supervisor(repository, factory, inbox)
        await(supervisor.start()); drain(supervisor); val first = factory.latest(original.id)
        val updated = withConfiguration(original, true, 2, 1024L, "secret-b"); repository.put(updated); supervisor.onCommitted(change(updated, BotConfigurationChangeKind.UPDATED)); drain(supervisor)
        val second = factory.latest(original.id)
        assertThat(first.dispatch(GatewayDispatch(2L, "C2C_MESSAGE_CREATE", "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"stale-generation\",\"d\":{}}"))).isFalse()
        supervisor.beforeActiveDatabaseChange()
        assertThat(second.dispatch(GatewayDispatch(3L, "C2C_MESSAGE_CREATE", "{\"op\":0,\"s\":3,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"stale-database\",\"d\":{}}"))).isFalse()
        assertThat(inbox.events()).isEmpty()
    }

    @Test
    fun environmentRevisionWritesNewRuntimeEventsToTheNewEnvironment() {
        val repository = InMemoryBotRepository(); val original = bot(1, true, 1, 512L, "secret-a"); repository.put(original)
        val factory = FakeRuntimeFactory(); val inbox = RecordingInboxRepository(); val supervisor = supervisor(repository, factory, inbox)
        await(supervisor.start()); drain(supervisor); val sandbox = withEnvironment(original, BotEnvironment.SANDBOX, 2L)
        repository.put(sandbox); supervisor.onCommitted(change(sandbox, BotConfigurationChangeKind.UPDATED)); drain(supervisor)
        val replacement = factory.latest(original.id); replacement.ready("session-sandbox", 1L); drain(supervisor)
        assertThat(replacement.dispatch(GatewayDispatch(2L, "C2C_MESSAGE_CREATE", "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"sandbox-event\",\"d\":{}}"))).isTrue()
        assertThat(inbox.events()).singleElement().extracting(InboxEvent::environment).isEqualTo(BotEnvironment.SANDBOX)
    }

    @Test
    fun periodicFullReconciliationFindsChangesWithoutNotification() {
        val repository = InMemoryBotRepository(); val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory, Duration.ofMillis(20))
        val configured = bot(1, true, 1, 512L, "secret-a"); val created = factory.createdLatch(configured.id)
        await(supervisor.start()); repository.put(configured)
        assertThat(created.await(2L, TimeUnit.SECONDS)).isTrue(); assertThat(factory.latest(configured.id).started()).isTrue()
    }

    @Test
    fun closeFencesEveryRuntimeAndIgnoresLaterCallbacks() {
        val repository = InMemoryBotRepository(); val first = bot(1, true, 1, 512L, "secret-a"); val second = bot(2, true, 1, 512L, "secret-b")
        repository.put(first); repository.put(second); val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory)
        await(supervisor.start()); drain(supervisor); val firstRuntime = factory.latest(first.id); val secondRuntime = factory.latest(second.id)
        supervisor.close(); firstRuntime.ready("late-session", 1L)
        assertThat(firstRuntime.stopped()).isTrue(); assertThat(secondRuntime.stopped()).isTrue(); assertThat(supervisor.status(first.id)).isNull(); assertThat(supervisor.status(second.id)).isNull()
    }

    @Test
    fun gracefulShutdownWaitsForTransportCleanupAfterSynchronousFencing() {
        val repository = InMemoryBotRepository(); val configured = bot(1, true, 1, 512L, "secret-a"); repository.put(configured)
        val factory = FakeRuntimeFactory(); val supervisor = supervisor(repository, factory); await(supervisor.start()); drain(supervisor)
        val runtime = factory.latest(configured.id); runtime.deferStop(); val shutdown = supervisor.shutdown()
        assertThat(runtime.stopped()).isTrue(); assertThat(shutdown.toCompletableFuture()).isNotDone(); runtime.completeStop(); await(shutdown); assertThat(shutdown.toCompletableFuture()).isCompleted()
    }

    private fun supervisor(repository: InMemoryBotRepository, factory: FakeRuntimeFactory) = supervisor(repository, factory, LONG_INTERVAL)

    private fun supervisor(repository: InMemoryBotRepository, factory: FakeRuntimeFactory, inboxRepository: EventInboxRepository): BotSupervisor =
        BotSupervisor(
            repository,
            factory,
            reconciliationInterval = LONG_INTERVAL,
            shutdownTimeout = SHUTDOWN_TIMEOUT,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            inboxRepository = inboxRepository,
        ).also(supervisors::add)

    private fun supervisor(repository: InMemoryBotRepository, factory: FakeRuntimeFactory, inboxRepository: EventInboxRepository, events: BotGatewayEventSink): BotSupervisor =
        BotSupervisor(
            repository,
            factory,
            reconciliationInterval = LONG_INTERVAL,
            shutdownTimeout = SHUTDOWN_TIMEOUT,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            inboxRepository = inboxRepository,
            gatewayEvents = events,
        ).also(supervisors::add)

    private fun supervisor(repository: InMemoryBotRepository, factory: FakeRuntimeFactory, reconciliationInterval: Duration): BotSupervisor =
        BotSupervisor(
            repository,
            factory,
            reconciliationInterval = reconciliationInterval,
            shutdownTimeout = SHUTDOWN_TIMEOUT,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        ).also(supervisors::add)

    private class FakeRuntimeFactory : BotRuntimeFactory {
        private val runtimes: ConcurrentMap<BotId, List<FakeRuntime>> = ConcurrentHashMap()
        private val attempts: ConcurrentMap<BotId, AtomicInteger> = ConcurrentHashMap()
        private val createdLatches: ConcurrentMap<BotId, CountDownLatch> = ConcurrentHashMap()
        private val creationFailures = ConcurrentHashMap<BotId, Boolean>()
        private val startFailures = ConcurrentHashMap<BotId, Boolean>()
        private val specificStartFailures = ConcurrentHashMap<BotId, BotRuntimeFailure>()

        override fun create(configuration: StoredBot, observer: BotRuntimeObserver): ManagedBotRuntime {
            val botId = configuration.id; attempts.computeIfAbsent(botId) { AtomicInteger() }.incrementAndGet()
            if (creationFailures.containsKey(botId)) throw IllegalStateException("factory failure")
            val runtime = FakeRuntime(observer, startFailures.containsKey(botId), specificStartFailures[botId])
            runtimes.compute(botId) { _, existing -> (existing ?: emptyList()) + runtime }
            createdLatches[botId]?.countDown()
            return runtime
        }

        fun failCreation(botId: BotId) { creationFailures[botId] = true }
        fun failInitialStart(botId: BotId) { startFailures[botId] = true }
        fun failInitialStart(botId: BotId, failure: BotRuntimeFailure) { specificStartFailures[botId] = failure }
        fun createdLatch(botId: BotId) = CountDownLatch(1).also { createdLatches[botId] = it }
        fun attempts(botId: BotId) = attempts[botId]?.get() ?: 0
        fun created(botId: BotId): List<FakeRuntime> = runtimes[botId] ?: emptyList()
        fun latest(botId: BotId) = created(botId).lastOrNull() ?: throw AssertionError("No runtime created for $botId")
    }

    private class FakeRuntime(
        private val observer: BotRuntimeObserver,
        private val exceptionalInitialStart: Boolean,
        private val specificStartFailure: BotRuntimeFailure?,
    ) : ManagedBotRuntime {
        private val started = AtomicBoolean()
        private val stopped = AtomicBoolean()
        @Volatile private var stopResult: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

        override fun start(): CompletionStage<Void> {
            started.set(true)
            if (specificStartFailure != null) {
                observer.onFailure(specificStartFailure)
                return CompletableFuture.failedFuture(IllegalStateException("specific initial start failure"))
            }
            observer.onStateChanged(BotRuntimeState.CONNECTING)
            if (exceptionalInitialStart) {
                observer.onReconnectScheduled()
                return CompletableFuture.failedFuture(IllegalStateException("initial connect failed"))
            }
            return CompletableFuture.completedFuture(null)
        }

        override fun stop(): CompletionStage<Void> = stopResult.also { stopped.set(true) }
        fun started() = started.get()
        fun stopped() = stopped.get()
        fun ready(sessionId: String, sequence: Long) = observer.onReady(BotSessionSnapshot(sessionId, sequence))
        fun heartbeat(sequence: Long) = observer.onHeartbeatAcknowledged(sequence)
        fun dispatch(sequence: Long) = observer.onDispatch(GatewayDispatch(sequence, "TEST_EVENT", "{}"))
        fun dispatch(dispatch: GatewayDispatch): Boolean = observer.acceptDispatch(dispatch).also { if (it) observer.onDispatch(dispatch) }
        fun fail(failure: BotRuntimeFailure) = observer.onFailure(failure)
        fun deferStop() { stopResult = CompletableFuture() }
        fun completeStop() { stopResult.complete(null) }
    }

    private class InMemoryBotRepository : BotRepository {
        private val bots: ConcurrentMap<BotId, StoredBot> = ConcurrentHashMap()
        @Volatile private var blockingRead: BlockingRead? = null

        override fun findById(id: BotId): StoredBot? {
            val result = bots[id]; val blocking = blockingRead
            if (blocking != null && blocking.botId == id && blocking.claimed.compareAndSet(false, true)) {
                blocking.started.countDown()
                try {
                    if (!blocking.release.await(2L, TimeUnit.SECONDS)) throw IllegalStateException("timed out waiting to release repository read")
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt(); throw IllegalStateException("repository read interrupted", exception)
                } finally { blockingRead = null }
            }
            return result
        }

        override fun findAll(): List<StoredBot> = bots.values.sortedBy { it.id.toString() }
        override fun findEnabled(): List<StoredBot> = findAll().filter { it.definition.enabled }
        override fun insert(bot: StoredBot) { if (bots.putIfAbsent(bot.id, bot) != null) throw IllegalStateException("duplicate bot") }
        override fun update(bot: StoredBot, expectedRevision: BotRevision): BotRevision {
            val result = AtomicReferenceResult()
            bots.compute(bot.id) { _, current ->
                if (current == null || current.definition.revision != expectedRevision) { result.failed = true; current } else {
                    val desired = bot.definition; val next = expectedRevision.next(); result.revision = next
                    StoredBot(BotDefinition(desired.id, desired.displayName, desired.appId, desired.environment, desired.intents, desired.shardSpec, desired.enabled, next, desired.createdAt, desired.updatedAt), bot.appSecret)
                }
            }
            if (result.failed) throw OptimisticLockException(bot.id, expectedRevision)
            return requireNotNull(result.revision)
        }
        override fun delete(id: BotId): Boolean = bots.remove(id) != null
        fun put(bot: StoredBot) { bots[bot.id] = bot }
        fun replaceAll(replacements: List<StoredBot>) { bots.clear(); replacements.forEach(::put) }
        fun blockNextRead(botId: BotId) = BlockingRead(botId, AtomicBoolean(), CountDownLatch(1), CountDownLatch(1)).also { blockingRead = it }
        private class AtomicReferenceResult { var failed = false; var revision: BotRevision? = null }
    }

    private class RecordingInboxRepository : EventInboxRepository {
        private val byKey = LinkedHashMap<InboxKey, InboxEvent>()
        private val byId = LinkedHashMap<UUID, InboxEvent>()
        private var failWrites = false
        @Synchronized override fun insertOrGet(event: IncomingEvent): InboxInsertResult {
            if (failWrites) throw IllegalStateException("inbox unavailable")
            val key = InboxKey(event.environment, event.botId, event.eventType, event.platformEventId)
            byKey[key]?.let { return InboxInsertResult(false, it) }
            val stored = InboxEvent(event.id, event.environment, event.botId, event.eventType, event.platformEventId, event.payload, InboxStatus.RECEIVED, 0, event.receivedAt, null, null, 0L, null, event.receivedAt, event.receivedAt)
            byKey[key] = stored; byId[stored.id] = stored; return InboxInsertResult(true, stored)
        }
        @Synchronized override fun findById(id: UUID): InboxEvent? = byId[id]
        @Synchronized override fun query(query: InboxQuery): InboxPage = InboxPage(byId.values.take(query.limit), null)
        override fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): InboxEvent? = null
        override fun claimNextOwned(
            leaseOwner: String,
            botLeaseOwner: String,
            now: Instant,
            leaseDuration: Duration,
        ): InboxEvent? = null
        override fun markDispatched(id: UUID, fencingToken: Long, now: Instant) = Unit
        override fun markRetry(
            id: UUID,
            fencingToken: Long,
            now: Instant,
            availableAt: Instant,
            error: String,
        ) = Unit
        override fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String) = Unit
        @Synchronized fun events(): List<InboxEvent> = byId.values.toList()
        @Synchronized fun failWrites() { failWrites = true }
        private data class InboxKey(val environment: BotEnvironment, val botId: BotId, val eventType: String, val platformEventId: String)
    }

    private data class BlockingRead(val botId: BotId, val claimed: AtomicBoolean, val started: CountDownLatch, val release: CountDownLatch)

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-18T08:00:00Z")
        val LONG_INTERVAL: Duration = Duration.ofDays(1)
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(1)
        fun drain(supervisor: BotSupervisor) = await(supervisor.start())
        fun await(stage: CompletionStage<Void>) { stage.toCompletableFuture().join() }
        fun change(bot: StoredBot, kind: BotConfigurationChangeKind) = BotConfigurationChange(bot.definition.id, bot.definition.revision, bot.definition.enabled, kind)
        fun bot(id: Long, enabled: Boolean, revision: Long, intents: Long, encryptedSecret: String): StoredBot {
            val botId = BotId.of(UUID(0L, id)); val createdAt = NOW.minusSeconds(60L)
            return StoredBot(BotDefinition(botId, "Bot $id", QqAppId.of("1905208$id"), BotEnvironment.PRODUCTION, GatewayIntents.of(intents), ShardSpec.single(), enabled, BotRevision.of(revision), createdAt, createdAt.plusSeconds(revision)), SecretCiphertext.of("v1.nonce.$encryptedSecret", "primary"))
        }
        fun withConfiguration(original: StoredBot, enabled: Boolean, revision: Long, intents: Long, encryptedSecret: String): StoredBot {
            val current = original.definition
            return StoredBot(BotDefinition(current.id, current.displayName, current.appId, current.environment, GatewayIntents.of(intents), current.shardSpec, enabled, BotRevision.of(revision), current.createdAt, current.updatedAt.plusSeconds(1L)), SecretCiphertext.of("v1.nonce.$encryptedSecret", "primary"))
        }
        fun withEnvironment(original: StoredBot, environment: BotEnvironment, revision: Long): StoredBot {
            val current = original.definition
            return StoredBot(BotDefinition(current.id, current.displayName, current.appId, environment, current.intents, current.shardSpec, current.enabled, BotRevision.of(revision), current.createdAt, current.updatedAt.plusSeconds(1L)), original.appSecret)
        }
    }
}
