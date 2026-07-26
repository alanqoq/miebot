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
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.IncomingEvent
import com.mieai.qqbot.persistence.lease.BotLease
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener
import com.mieai.qqbot.runtime.event.BotGatewayEvent
import com.mieai.qqbot.runtime.event.BotGatewayEventSink
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/** Reconciles persisted bot desired state into isolated, generation-fenced runtimes. */
class BotSupervisor(
    private val repository: BotRepository,
    private val runtimeFactory: BotRuntimeFactory,
    reconciliationInterval: Duration = DEFAULT_RECONCILIATION_INTERVAL,
    shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
    private val clock: Clock = Clock.systemUTC(),
    private val inboxRepository: EventInboxRepository? = null,
    private val leaseRepository: BotLeaseRepository? = null,
    leaseOwnerId: String? = null,
    leaseDuration: Duration? = null,
    private val pluginHashes: () -> Map<String, String> = { emptyMap() },
    private val gatewayEvents: BotGatewayEventSink = BotGatewayEventSink.noop(),
) : BotConfigurationChangeListener, AutoCloseable {
    private val leaseOwnerId: String? = if (leaseRepository == null) null else requireLeaseOwner(leaseOwnerId)
    private val leaseDuration: Duration? =
        if (leaseRepository == null) null else requirePositive(leaseDuration, "leaseDuration")
    private val leases: ConcurrentMap<BotId, BotLease> = ConcurrentHashMap()
    private val reconciliationInterval = requirePositive(reconciliationInterval, "reconciliationInterval")
    private val shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout")
    private val reconciler = ScheduledThreadPoolExecutor(1, daemonThreadFactory())
    private val slots: ConcurrentMap<BotId, Slot> = ConcurrentHashMap()
    private val databaseEpoch = AtomicLong()
    private val configurationSignal = AtomicLong()
    private val lifecycle = AtomicReference(Lifecycle.NEW)
    private val shutdown = AtomicReference<CompletableFuture<Void>?>(null)
    private val topologyMonitor = Any()

    @Volatile
    private var databaseTransitionActive = false

    @Volatile
    private var periodicReconciliation: ScheduledFuture<*>? = null

    init {
        if (leaseRepository != null) {
            require(this.leaseDuration!! > this.reconciliationInterval) {
                "leaseDuration must exceed reconciliationInterval"
            }
        }
        reconciler.removeOnCancelPolicy = true
        reconciler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        reconciler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    /** Loads current desired state and starts all enabled bots. */
    fun start(): CompletionStage<Void> {
        if (lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.RUNNING)) {
            val initial = submit(Runnable(::reconcileAllInternal))
            periodicReconciliation = reconciler.scheduleWithFixedDelay(
                Runnable(::periodicReconcile),
                reconciliationInterval.toNanos(),
                reconciliationInterval.toNanos(),
                TimeUnit.NANOSECONDS,
            )
            return initial
        }
        if (lifecycle.get() == Lifecycle.RUNNING) return barrier()
        return CompletableFuture.failedFuture(
            IllegalStateException("Bot supervisor has already been stopped"),
        )
    }

    /** Re-reads and reconciles one bot on the supervisor's single reconciliation thread. */
    fun reconcile(botId: BotId): CompletionStage<Void> {
        return submit(Runnable { reconcileOneSafely(botId, false) })
    }

    /** Re-reads all configured bots and stops runtimes absent from the active database. */
    fun reconcileAll(): CompletionStage<Void> = submit(Runnable(::reconcileAllInternal))

    /** Forces a fresh runtime for the latest enabled revision. */
    fun restart(botId: BotId): CompletionStage<Void> {
        return submit(Runnable { reconcileOneSafely(botId, true) })
    }

    /** Fences every old-database runtime and reconciles from the new active database. */
    fun activeDatabaseChanged(): CompletionStage<Void> {
        val retired = synchronized(topologyMonitor) {
            val isolated = isolateAllForNewDatabaseLocked()
            databaseTransitionActive = false
            isolated
        }
        retired.forEach { runtime -> stopBestEffort(runtime, null) }
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return CompletableFuture.completedFuture<Void>(null)
        }
        return submit(Runnable(::reconcileAllInternal))
    }

    /** Synchronously fences the current runtime topology before a DataSource delegate is swapped. */
    fun beforeActiveDatabaseChange() {
        val retired = synchronized(topologyMonitor) {
            if (lifecycle.get() != Lifecycle.RUNNING) return
            check(!databaseTransitionActive) { "A database transition is already in progress" }
            databaseTransitionActive = true
            try {
                isolateAllForNewDatabaseLocked()
            } catch (failure: Throwable) {
                databaseTransitionActive = false
                throw failure
            }
        }
        retired.forEach { runtime -> stopBestEffort(runtime, null) }
    }

    /** Synchronous configuration notification used by BotConfigurationService. */
    override fun onCommitted(change: BotConfigurationChange) {
        if (lifecycle.get() != Lifecycle.RUNNING) return
        try {
            val disabled = synchronized(topologyMonitor) {
                if (databaseTransitionActive) return
                configurationSignal.incrementAndGet()
                slots[change.botId]?.noteCommitted(change, databaseEpoch.get(), clock.instant())
            }
            if (disabled != null) stopBestEffort(disabled, change.botId)
            if (!change.enabled) releaseLease(change.botId)
            submitWithoutResult(Runnable { reconcileOneSafely(change.botId, false) })
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Unable to enqueue committed bot configuration {} at revision {} ({})",
                change.botId,
                change.revision.value,
                exception.javaClass.simpleName,
            )
        }
    }

    fun status(botId: BotId): BotRuntimeStatus? = slots[botId]?.snapshot()

    fun statuses(): List<BotRuntimeStatus> =
        slots.values.map(Slot::snapshot).sortedBy { status -> status.botId.toString() }

    fun summary(): BotRuntimeSummary = BotRuntimeSummary.from(statuses(), clock.instant())

    /** Completes after all installed runtimes have stopped or timed out. */
    fun shutdown(): CompletionStage<Void> {
        shutdown.get()?.let { return it.minimalCompletionStage() }
        val result = CompletableFuture<Void>()
        if (!shutdown.compareAndSet(null, result)) return shutdown.get()!!.minimalCompletionStage()

        lifecycle.getAndUpdate { current ->
            if (current == Lifecycle.CLOSED) Lifecycle.CLOSED else Lifecycle.CLOSING
        }
        periodicReconciliation?.cancel(false)

        val retired = isolateAllForShutdown()
        val stopping = retired.map { runtime -> stopForShutdown(runtime).toCompletableFuture() }
        reconciler.shutdown()

        CompletableFuture.allOf(*stopping.toTypedArray())
            .orTimeout(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)
            .handle<Void> { _, _ -> null }
            .whenComplete { _, _ ->
                lifecycle.set(Lifecycle.CLOSED)
                reconciler.shutdownNow()
                result.complete(null)
            }
        return result.minimalCompletionStage()
    }

    override fun close() {
        try {
            shutdown().toCompletableFuture().get(
                shutdownTimeout.plusSeconds(1L).toNanos(),
                TimeUnit.NANOSECONDS,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            reconciler.shutdownNow()
        } catch (_: ExecutionException) {
            reconciler.shutdownNow()
        } catch (_: TimeoutException) {
            reconciler.shutdownNow()
        }
    }

    private fun periodicReconcile() {
        if (lifecycle.get() != Lifecycle.RUNNING) return
        try {
            reconcileAllInternal()
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Periodic bot reconciliation failed ({})",
                exception.javaClass.simpleName,
            )
        }
    }

    private fun reconcileAllInternal() {
        if (lifecycle.get() != Lifecycle.RUNNING || databaseTransitionActive) return
        val configured = repository.findAll().toList()
        val botIds = LinkedHashSet<BotId>()
        configured.forEach { bot -> botIds.add(bot.id) }
        botIds.addAll(slots.keys)
        botIds.forEach { botId -> reconcileOneSafely(botId, false) }
    }

    private fun reconcileOneSafely(botId: BotId, forceRestart: Boolean) {
        try {
            reconcileOneInternal(botId, forceRestart)
        } catch (exception: RuntimeException) {
            slots[botId]?.let { slot ->
                slot.recordFailure(
                    slot.currentGeneration,
                    slot.databaseEpoch,
                    BotRuntimeFailure(
                        "CONFIGURATION_READ_FAILED",
                        "The latest bot configuration could not be read",
                        true,
                    ),
                    false,
                    clock.instant(),
                )
            }
            LOGGER.warn(
                "Bot reconciliation failed for {} ({})",
                botId,
                exception.javaClass.simpleName,
            )
        }
    }

    private fun reconcileOneInternal(botId: BotId, forceRestart: Boolean) {
        if (lifecycle.get() != Lifecycle.RUNNING || databaseTransitionActive) return
        val epoch = databaseEpoch.get()
        val signal = configurationSignal.get()
        val loaded = repository.findById(botId)
        if (databaseEpoch.get() != epoch || lifecycle.get() != Lifecycle.RUNNING) return
        if (configurationSignal.get() != signal) {
            retryReconciliation(botId)
            return
        }
        if (loaded == null) {
            releaseLease(botId)
            if (!removeMissing(botId, epoch, signal)) retryReconciliation(botId)
            return
        }

        val stored = loaded
        val slot = synchronized(topologyMonitor) {
            if (databaseTransitionActive ||
                databaseEpoch.get() != epoch ||
                lifecycle.get() != Lifecycle.RUNNING
            ) {
                return
            }
            if (configurationSignal.get() != signal) {
                null
            } else {
                slots.computeIfAbsent(botId) { Slot.from(stored, epoch, clock.instant()) }
            }
        }
        if (slot == null) {
            retryReconciliation(botId)
            return
        }
        if (databaseTransitionActive || databaseEpoch.get() != epoch) return

        if (stored.definition.enabled) {
            if (!ensureLease(stored)) {
                slot.leaseUnavailable(stored, clock.instant())?.let { retired ->
                    stopBestEffort(retired, botId)
                }
                return
            }
        } else {
            releaseLease(botId)
        }

        val decision = slot.decide(stored, epoch, forceRestart, clock.instant())
        if (decision.stale) return
        decision.retired?.let { retired -> stopBestEffort(retired, botId) }
        if (!decision.start) return

        val observer = SlotObserver(slot, decision.generation, epoch)
        val created = try {
            runtimeFactory.create(stored, observer)
        } catch (_: RuntimeException) {
            slot.creationFailed(decision.generation, epoch, FACTORY_FAILURE, clock.instant())
            return
        }

        val installed = synchronized(topologyMonitor) {
            lifecycle.get() == Lifecycle.RUNNING &&
                !databaseTransitionActive &&
                databaseEpoch.get() == epoch &&
                slots[botId] === slot &&
                slot.install(decision.generation, epoch, created)
        }
        if (!installed) {
            stopBestEffort(created, botId)
            return
        }

        val starting = try {
            created.start()
        } catch (_: RuntimeException) {
            handleStartFailure(slot, decision.generation, epoch)
            return
        }
        starting.whenComplete { _, failure ->
            if (failure != null) {
                dispatchObserver(Runnable {
                    handleStartFailure(slot, decision.generation, epoch)
                })
            }
        }
    }

    private fun handleStartFailure(slot: Slot, generation: Long, epoch: Long) {
        if (!isCurrent(slot, generation, epoch)) return
        slot.recordStartFailureIfAbsent(generation, epoch, START_FAILURE, clock.instant())
    }

    private fun removeMissing(botId: BotId, epoch: Long, signal: Long): Boolean {
        val removed = synchronized(topologyMonitor) {
            if (databaseTransitionActive ||
                databaseEpoch.get() != epoch ||
                configurationSignal.get() != signal
            ) {
                return false
            }
            slots.remove(botId)
        }
        if (removed != null) {
            releaseLease(botId)
            removed.detach(clock.instant(), BotRuntimeState.STOPPED)?.let { runtime ->
                stopBestEffort(runtime, botId)
            }
        }
        return true
    }

    private fun retryReconciliation(botId: BotId) {
        submitWithoutResult(Runnable { reconcileOneSafely(botId, false) })
    }

    private fun isolateAllForNewDatabase(): List<ManagedBotRuntime> =
        synchronized(topologyMonitor) { isolateAllForNewDatabaseLocked() }

    private fun isolateAllForNewDatabaseLocked(): List<ManagedBotRuntime> {
        val retired = ArrayList<ManagedBotRuntime>()
        databaseEpoch.incrementAndGet()
        configurationSignal.incrementAndGet()
        val now = clock.instant()
        slots.values.forEach { slot ->
            slot.detach(now, BotRuntimeState.STOPPED)?.let(retired::add)
        }
        slots.clear()
        releaseAllLeases()
        return retired
    }

    private fun isolateAllForShutdown(): List<ManagedBotRuntime> {
        val retired = ArrayList<ManagedBotRuntime>()
        synchronized(topologyMonitor) {
            databaseTransitionActive = false
            databaseEpoch.incrementAndGet()
            configurationSignal.incrementAndGet()
            val now = clock.instant()
            slots.values.forEach { slot ->
                slot.detach(now, BotRuntimeState.STOPPING)?.let(retired::add)
            }
            slots.clear()
            releaseAllLeases()
        }
        return retired
    }

    private fun stopForShutdown(runtime: ManagedBotRuntime): CompletionStage<Void> {
        return try {
            val stopping = runtime.stop()
            stopping.handle<Void> { _, _ -> null }
        } catch (_: RuntimeException) {
            CompletableFuture.completedFuture<Void>(null)
        }
    }

    private fun stopBestEffort(runtime: ManagedBotRuntime, botId: BotId?) {
        try {
            val stopping = runtime.stop()
            stopping.whenComplete { _, failure ->
                if (failure != null && botId != null) {
                    LOGGER.warn("Bot runtime cleanup failed for {}", botId)
                }
            }
        } catch (exception: RuntimeException) {
            if (botId != null) {
                LOGGER.warn(
                    "Bot runtime cleanup could not start for {} ({})",
                    botId,
                    exception.javaClass.simpleName,
                )
            }
        }
    }

    private fun isCurrent(slot: Slot, generation: Long, epoch: Long): Boolean =
        lifecycle.get() == Lifecycle.RUNNING &&
            databaseEpoch.get() == epoch &&
            slots[slot.botId] === slot &&
            slot.accepts(generation, epoch)

    private fun ensureLease(stored: StoredBot): Boolean {
        val repository = leaseRepository ?: return true
        val botId = stored.id
        val now = clock.instant()
        var current = leases[botId]
        try {
            val desiredShard = stored.definition.shardSpec.index
            val localPluginHashes = pluginHashes().toMap()
            if (current != null && current.shardIndex != desiredShard) {
                releaseLease(botId)
                current = null
            }
            if (current != null &&
                repository.renew(current, now, leaseDuration!!, localPluginHashes)
            ) {
                return true
            }
            val acquired = repository.acquire(
                botId,
                desiredShard,
                leaseOwnerId!!,
                now,
                leaseDuration!!,
                localPluginHashes,
            )
            if (acquired != null) {
                leases[botId] = acquired
                return true
            }
            if (current != null) repository.release(current)
            leases.remove(botId)
            return false
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Unable to acquire or renew bot lease for {} ({})",
                botId,
                exception.javaClass.simpleName,
            )
            leases.remove(botId)
            return false
        }
    }

    private fun releaseLease(botId: BotId) {
        val repository = leaseRepository ?: return
        val lease = leases.remove(botId) ?: return
        try {
            repository.release(lease)
        } catch (exception: RuntimeException) {
            LOGGER.debug("Unable to release bot lease for {}", botId, exception)
        }
    }

    private fun releaseAllLeases() {
        val repository = leaseRepository ?: return
        ArrayList(leases.keys).forEach(::releaseLease)
        try {
            repository.unregisterPluginHashes(leaseOwnerId!!)
        } catch (exception: RuntimeException) {
            LOGGER.debug("Unable to unregister plugin hashes", exception)
        }
    }

    private fun submit(task: Runnable): CompletionStage<Void> {
        val result = CompletableFuture<Void>()
        if (lifecycle.get() != Lifecycle.RUNNING) {
            result.complete(null)
            return result.minimalCompletionStage()
        }
        try {
            reconciler.execute {
                if (lifecycle.get() != Lifecycle.RUNNING) {
                    result.complete(null)
                    return@execute
                }
                try {
                    task.run()
                    result.complete(null)
                } catch (exception: RuntimeException) {
                    result.completeExceptionally(exception)
                }
            }
        } catch (_: RejectedExecutionException) {
            result.complete(null)
        }
        return result.minimalCompletionStage()
    }

    private fun barrier(): CompletionStage<Void> = submit(Runnable {})

    private fun submitWithoutResult(task: Runnable) {
        submit(task).exceptionally { null }
    }

    private fun dispatchObserver(task: Runnable) {
        if (lifecycle.get() == Lifecycle.RUNNING) submitWithoutResult(task)
    }

    private enum class Lifecycle {
        NEW,
        RUNNING,
        CLOSING,
        CLOSED,
    }

    private data class RuntimeFingerprint(
        val appId: QqAppId,
        val environment: BotEnvironment,
        val intents: GatewayIntents,
        val shardSpec: ShardSpec,
        val appSecret: SecretCiphertext,
    ) {
        companion object {
            fun from(stored: StoredBot): RuntimeFingerprint {
                val definition = stored.definition
                return RuntimeFingerprint(
                    definition.appId,
                    definition.environment,
                    definition.intents,
                    definition.shardSpec,
                    stored.appSecret,
                )
            }
        }
    }

    private data class ReconcileDecision(
        val stale: Boolean,
        val start: Boolean,
        val generation: Long,
        val retired: ManagedBotRuntime?,
    ) {
        companion object {
            fun staleDecision() = ReconcileDecision(true, false, -1L, null)
            fun retained() = ReconcileDecision(false, false, -1L, null)
            fun stopped(retired: ManagedBotRuntime?) = ReconcileDecision(false, false, -1L, retired)
            fun starting(generation: Long, retired: ManagedBotRuntime?) =
                ReconcileDecision(false, true, generation, retired)
        }
    }

    private class Slot private constructor(stored: StoredBot, private val epoch: Long, now: Instant) {
        val botId = stored.id
        var environment = stored.definition.environment
            private set
        private var announcedRevision = stored.definition.revision
        private var configurationRevision = stored.definition.revision
        private var desiredEnabled = stored.definition.enabled
        private var generation = 0L
        private var runtime: ManagedBotRuntime? = null
        private var runtimeFingerprint: RuntimeFingerprint? = null
        private var attemptedRevision: BotRevision? = null
        private var attemptedFingerprint: RuntimeFingerprint? = null
        private var state = if (desiredEnabled) BotRuntimeState.STOPPED else BotRuntimeState.DISABLED
        private var stateChangedAt = now
        private var startedAt: Instant? = null
        private var readyAt: Instant? = null
        private var lastHeartbeatAt: Instant? = null
        private var lastDispatchAt: Instant? = null
        private var session: BotSessionSnapshot? = null
        private var reconnectCount = 0L
        private var lastFailure: BotRuntimeFailure? = null
        private var lastFailureAt: Instant? = null

        @Synchronized
        fun fallbackPlatformEventId(
            expectedGeneration: Long,
            dispatchSessionId: String?,
            sequence: Long,
        ): String {
            val sessionId = if (dispatchSessionId.isNullOrBlank()) {
                session?.sessionId ?: "generation-$expectedGeneration"
            } else {
                dispatchSessionId
            }
            return "gateway:$sessionId:$sequence"
        }

        val databaseEpoch: Long
            @Synchronized get() = epoch

        val currentGeneration: Long
            @Synchronized get() = generation

        @Synchronized
        fun noteCommitted(
            change: BotConfigurationChange,
            currentEpoch: Long,
            now: Instant,
        ): ManagedBotRuntime? {
            if (epoch != currentEpoch || change.revision < announcedRevision) return null
            announcedRevision = change.revision
            if (change.enabled) return null

            configurationRevision = change.revision
            desiredEnabled = false
            attemptedRevision = change.revision
            attemptedFingerprint = null
            generation++
            val detached = runtime
            runtime = null
            runtimeFingerprint = null
            setState(BotRuntimeState.DISABLED, now)
            return detached
        }

        @Synchronized
        fun decide(
            stored: StoredBot,
            currentEpoch: Long,
            forceRestart: Boolean,
            now: Instant,
        ): ReconcileDecision {
            val definition = stored.definition
            if (epoch != currentEpoch || definition.revision < announcedRevision) {
                return ReconcileDecision.staleDecision()
            }
            announcedRevision = definition.revision
            configurationRevision = definition.revision
            desiredEnabled = definition.enabled
            environment = definition.environment
            val desiredFingerprint = RuntimeFingerprint.from(stored)

            if (!desiredEnabled) {
                attemptedRevision = configurationRevision
                attemptedFingerprint = desiredFingerprint
                generation++
                val detached = runtime
                runtime = null
                runtimeFingerprint = null
                setState(BotRuntimeState.DISABLED, now)
                return ReconcileDecision.stopped(detached)
            }
            if (!forceRestart && runtime != null && desiredFingerprint == runtimeFingerprint) {
                return ReconcileDecision.retained()
            }
            if (!forceRestart &&
                runtime == null &&
                state == BotRuntimeState.FAILED &&
                configurationRevision == attemptedRevision &&
                desiredFingerprint == attemptedFingerprint
            ) {
                return ReconcileDecision.retained()
            }

            generation++
            val detached = runtime
            runtime = null
            runtimeFingerprint = null
            attemptedRevision = configurationRevision
            attemptedFingerprint = desiredFingerprint
            startedAt = now
            readyAt = null
            lastHeartbeatAt = null
            lastDispatchAt = null
            session = null
            reconnectCount = 0L
            lastFailure = null
            lastFailureAt = null
            setState(BotRuntimeState.STARTING, now)
            return ReconcileDecision.starting(generation, detached)
        }

        @Synchronized
        fun install(
            expectedGeneration: Long,
            currentEpoch: Long,
            created: ManagedBotRuntime,
        ): Boolean {
            if (!accepts(expectedGeneration, currentEpoch) ||
                !desiredEnabled ||
                runtime != null ||
                configurationRevision != announcedRevision
            ) {
                return false
            }
            runtime = created
            runtimeFingerprint = attemptedFingerprint
            return true
        }

        @Synchronized
        fun accepts(expectedGeneration: Long, currentEpoch: Long): Boolean =
            epoch == currentEpoch && generation == expectedGeneration

        @Synchronized
        fun detach(now: Instant, detachedState: BotRuntimeState): ManagedBotRuntime? {
            generation++
            val detached = runtime
            runtime = null
            runtimeFingerprint = null
            desiredEnabled = false
            setState(detachedState, now)
            return detached
        }

        @Synchronized
        fun leaseUnavailable(stored: StoredBot, now: Instant): ManagedBotRuntime? {
            val definition = stored.definition
            announcedRevision = definition.revision
            configurationRevision = definition.revision
            desiredEnabled = true
            environment = definition.environment
            generation++
            val detached = runtime
            runtime = null
            runtimeFingerprint = null
            attemptedRevision = null
            attemptedFingerprint = null
            lastFailure = LEASE_NOT_ACQUIRED
            lastFailureAt = now
            setState(BotRuntimeState.STOPPED, now)
            return detached
        }

        @Synchronized
        fun stateChanged(
            expectedGeneration: Long,
            currentEpoch: Long,
            next: BotRuntimeState,
            now: Instant,
        ) {
            if (!accepts(expectedGeneration, currentEpoch) || !desiredEnabled) return
            setState(next, now)
            if (next == BotRuntimeState.ONLINE && readyAt == null) readyAt = now
        }

        @Synchronized
        fun ready(
            expectedGeneration: Long,
            currentEpoch: Long,
            snapshot: BotSessionSnapshot,
            now: Instant,
        ) {
            if (!accepts(expectedGeneration, currentEpoch) || !desiredEnabled) return
            session = snapshot
            readyAt = now
            setState(BotRuntimeState.ONLINE, now)
        }

        @Synchronized
        fun heartbeat(
            expectedGeneration: Long,
            currentEpoch: Long,
            sequence: Long?,
            now: Instant,
        ) {
            if (accepts(expectedGeneration, currentEpoch) && desiredEnabled) {
                lastHeartbeatAt = now
                updateSequence(sequence)
            }
        }

        @Synchronized
        fun dispatch(
            expectedGeneration: Long,
            currentEpoch: Long,
            sequence: Long,
            now: Instant,
        ) {
            if (accepts(expectedGeneration, currentEpoch) && desiredEnabled) {
                lastDispatchAt = now
                updateSequence(sequence)
            }
        }

        @Synchronized
        fun reconnect(expectedGeneration: Long, currentEpoch: Long, now: Instant) {
            if (!accepts(expectedGeneration, currentEpoch) || !desiredEnabled) return
            reconnectCount = Math.incrementExact(reconnectCount)
            setState(BotRuntimeState.RECONNECTING, now)
        }

        @Synchronized
        fun recordFailure(
            expectedGeneration: Long,
            currentEpoch: Long,
            failure: BotRuntimeFailure,
            terminal: Boolean,
            now: Instant,
        ) {
            if (!accepts(expectedGeneration, currentEpoch)) return
            lastFailure = failure
            lastFailureAt = now
            if (terminal || state == BotRuntimeState.STARTING) setState(BotRuntimeState.FAILED, now)
        }

        @Synchronized
        fun creationFailed(
            expectedGeneration: Long,
            currentEpoch: Long,
            failure: BotRuntimeFailure,
            now: Instant,
        ) {
            if (!accepts(expectedGeneration, currentEpoch)) return
            lastFailure = failure
            lastFailureAt = now
            setState(BotRuntimeState.FAILED, now)
            generation++
        }

        @Synchronized
        fun recordStartFailureIfAbsent(
            expectedGeneration: Long,
            currentEpoch: Long,
            failure: BotRuntimeFailure,
            now: Instant,
        ) {
            if (!accepts(expectedGeneration, currentEpoch) ||
                state != BotRuntimeState.STARTING ||
                lastFailure != null
            ) {
                return
            }
            lastFailure = failure
            lastFailureAt = now
            setState(BotRuntimeState.FAILED, now)
        }

        @Synchronized
        fun snapshot(): BotRuntimeStatus = BotRuntimeStatus(
            botId,
            configurationRevision,
            desiredEnabled,
            state,
            stateChangedAt,
            startedAt,
            readyAt,
            lastHeartbeatAt,
            lastDispatchAt,
            session,
            reconnectCount,
            lastFailure,
            lastFailureAt,
        )

        private fun setState(next: BotRuntimeState, now: Instant) {
            if (state != next) {
                state = next
                stateChangedAt = now
            }
        }

        private fun updateSequence(nextSequence: Long?) {
            val current = session
            if (current != null && nextSequence != null && nextSequence >= current.sequence) {
                session = current.withSequence(nextSequence)
            }
        }

        companion object {
            fun from(stored: StoredBot, epoch: Long, now: Instant) = Slot(stored, epoch, now)
        }
    }

    private inner class SlotObserver(
        private val slot: Slot,
        private val generation: Long,
        private val epoch: Long,
    ) : BotRuntimeObserver {
        override fun onStateChanged(state: BotRuntimeState) {
            dispatchObserver(Runnable {
                if (isCurrent(slot, generation, epoch)) {
                    slot.stateChanged(generation, epoch, state, clock.instant())
                }
            })
        }

        override fun onReady(snapshot: BotSessionSnapshot) {
            dispatchObserver(Runnable {
                if (isCurrent(slot, generation, epoch)) {
                    slot.ready(generation, epoch, snapshot, clock.instant())
                }
            })
        }

        override fun onHeartbeatAcknowledged(sequence: Long?) {
            require(sequence == null || sequence >= 0L) { "sequence must not be negative" }
            dispatchObserver(Runnable {
                if (isCurrent(slot, generation, epoch)) {
                    slot.heartbeat(generation, epoch, sequence, clock.instant())
                }
            })
        }

        override fun acceptDispatch(dispatch: GatewayDispatch): Boolean {
            require(dispatch.sequence >= 0L) { "sequence must not be negative" }
            synchronized(topologyMonitor) {
                if (databaseTransitionActive) return false
                val inbox = inboxRepository ?: return true
                if (!isCurrent(slot, generation, epoch)) return false
                val receivedAt = clock.instant()
                val rawPlatformEventId = dispatch.platformEventId
                val platformEventId = boundedPlatformEventId(
                    if (rawPlatformEventId.isNullOrBlank()) {
                        slot.fallbackPlatformEventId(
                            generation,
                            dispatch.sessionId,
                            dispatch.sequence,
                        )
                    } else {
                        rawPlatformEventId
                    },
                )
                val event = IncomingEvent(
                    UUID.randomUUID(),
                    slot.environment,
                    slot.botId,
                    dispatch.eventType,
                    platformEventId,
                    dispatch.rawPayload,
                    receivedAt,
                )
                try {
                    val stored = inbox.insertOrGet(event)
                    if (stored.inserted) {
                        gatewayEvents.publish(BotGatewayEvent(slot.botId, dispatch, receivedAt))
                    }
                    return true
                } catch (exception: RuntimeException) {
                    slot.recordFailure(
                        generation,
                        epoch,
                        INBOX_PERSISTENCE_FAILURE,
                        false,
                        receivedAt,
                    )
                    LOGGER.warn(
                        "Unable to persist Gateway dispatch for bot {} ({}); retaining the old sequence for retry",
                        slot.botId,
                        exception.javaClass.simpleName,
                    )
                    return false
                }
            }
        }

        private fun boundedPlatformEventId(value: String): String {
            if (value.codePointCount(0, value.length) <= 255) return value
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(StandardCharsets.UTF_8))
                val hex = StringBuilder("sha256:")
                for (item in digest) hex.append(String.format(Locale.ROOT, "%02x", item))
                return hex.toString()
            } catch (exception: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", exception)
            }
        }

        override fun onDispatch(dispatch: GatewayDispatch) {
            dispatchObserver(Runnable {
                if (isCurrent(slot, generation, epoch)) {
                    slot.dispatch(generation, epoch, dispatch.sequence, clock.instant())
                }
            })
        }

        override fun onReconnectScheduled() {
            dispatchObserver(Runnable {
                if (isCurrent(slot, generation, epoch)) {
                    slot.reconnect(generation, epoch, clock.instant())
                }
            })
        }

        override fun onFailure(failure: BotRuntimeFailure) {
            dispatchObserver(Runnable {
                if (isCurrent(slot, generation, epoch)) {
                    slot.recordFailure(generation, epoch, failure, false, clock.instant())
                }
            })
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(BotSupervisor::class.java)
        val DEFAULT_RECONCILIATION_INTERVAL: Duration = Duration.ofSeconds(30)
        val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(10)
        val START_FAILURE = BotRuntimeFailure(
            "RUNTIME_START_FAILED",
            "The bot runtime could not complete its initial start",
            true,
        )
        val FACTORY_FAILURE = BotRuntimeFailure(
            "RUNTIME_CONFIGURATION_FAILED",
            "The bot runtime could not be created",
            false,
        )
        val INBOX_PERSISTENCE_FAILURE = BotRuntimeFailure(
            "INBOX_PERSISTENCE_FAILED",
            "The latest QQ Gateway event could not be persisted; the session will retry",
            true,
        )
        val LEASE_NOT_ACQUIRED = BotRuntimeFailure(
            "LEASE_NOT_ACQUIRED",
            "Another application instance currently owns this bot shard",
            true,
        )

        fun requireLeaseOwner(value: String?): String {
            if (value == null ||
                value.isBlank() ||
                value.length > 255 ||
                value.codePoints().anyMatch { Character.isWhitespace(it) } ||
                value.codePoints().anyMatch { Character.isISOControl(it) }
            ) {
                throw IllegalArgumentException("leaseOwnerId is invalid")
            }
            return value
        }

        fun requirePositive(value: Duration?, name: String): Duration {
            val checked = requireNotNull(value) { "$name must not be null" }
            require(!checked.isZero && !checked.isNegative) { "$name must be positive" }
            checked.toNanos()
            return checked
        }

        fun daemonThreadFactory(): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "qqbot-supervisor").apply { isDaemon = true }
        }
    }
}
