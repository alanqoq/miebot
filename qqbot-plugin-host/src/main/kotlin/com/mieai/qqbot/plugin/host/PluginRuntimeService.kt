package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.plugin.PluginDelivery
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HashMap
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import org.slf4j.LoggerFactory

/** Durable Inbox-to-plugin pipeline with bounded retries and fenced database transitions. */
class PluginRuntimeService(
    private val host: Pf4jPluginHost,
    private val inbox: EventInboxRepository,
    private val bindings: BotPluginBindingRepository,
    private val deliveries: PluginDeliveryRepository,
    private val scheduler: ScheduledExecutorService,
    private val clock: Clock,
    pollInterval: Duration,
    leaseDuration: Duration,
    executionTimeout: Duration,
    cancellationGrace: Duration,
    maxAttempts: Int,
    batchSize: Int,
    private val botLeases: BotLeaseRepository?,
    instanceId: String?,
) : AutoCloseable {
    private val pollInterval = positive(pollInterval, "pollInterval")
    private val leaseDuration = positive(leaseDuration, "leaseDuration")
    private val executionTimeout = positive(executionTimeout, "executionTimeout")
    private val cancellationGrace = positive(cancellationGrace, "cancellationGrace")
    private val maxAttempts: Int
    private val batchSize: Int
    private val instanceId = if (botLeases == null) null else requireNotNull(instanceId) {
        "instanceId must not be null"
    }
    private val workerId = "plugin-worker-${UUID.randomUUID()}"
    private val running = AtomicBoolean()
    private val transitionMonitor = Object()
    private val deletingBots = HashSet<BotId>()
    private val deletionQuiescenceFailures = HashSet<BotId>()
    private val mutatingBindings = HashSet<UUID>()
    private val mutationQuiescenceFailures = HashSet<UUID>()

    @Volatile
    private var databaseTransitionActive = false

    private var polling: ScheduledFuture<*>? = null

    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
        require(batchSize in 1..1000) { "batchSize is invalid" }
        this.maxAttempts = maxAttempts
        this.batchSize = batchSize
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        host.start()
        polling = scheduler.scheduleWithFixedDelay(
            Runnable(::tickSafely),
            0,
            pollInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    fun isRunning(): Boolean = running.get()

    fun beforeActiveDatabaseChange() {
        synchronized(transitionMonitor) {
            databaseTransitionActive = true
            host.invalidateAll()
        }
    }

    fun activeDatabaseChanged() {
        synchronized(transitionMonitor) {
            try {
                host.reload()
            } finally {
                databaseTransitionActive = false
            }
        }
    }

    fun bindingChanged(bindingId: UUID) {
        host.invalidate(bindingId)
        val now = clock.instant()
        bindings.findById(bindingId)?.let { binding ->
            if (binding.enabled && binding.runtimeState.runnable()) {
                deliveries.resumeForBinding(bindingId, now)
            } else {
                deliveries.pauseForBinding(
                    bindingId,
                    now,
                    binding.runtimeError ?: "Plugin binding is paused",
                )
            }
        }
    }

    /** Fences one binding locally and requires every accepted callback to become idle. */
    fun beforeBindingMutation(bindingId: UUID) {
        synchronized(transitionMonitor) {
            mutatingBindings.add(bindingId)
            try {
                host.quiesceBindingStrict(bindingId)
                mutationQuiescenceFailures.remove(bindingId)
            } catch (failure: RuntimeException) {
                mutationQuiescenceFailures.add(bindingId)
                throw failure
            }
        }
    }

    /** Re-enables a binding when a file or durable mutation failed after local quiescence. */
    fun bindingMutationAborted(bindingId: UUID) {
        synchronized(transitionMonitor) {
            if (!mutationQuiescenceFailures.remove(bindingId)) mutatingBindings.remove(bindingId)
        }
    }

    /** Releases transient mutation state after the binding and its files reached one revision. */
    fun bindingMutationCompleted(bindingId: UUID) {
        synchronized(transitionMonitor) {
            mutationQuiescenceFailures.remove(bindingId)
            mutatingBindings.remove(bindingId)
        }
    }

    /** Fences one bot locally and requires every accepted plugin callback to become idle. */
    fun beforeBotDeletion(botId: BotId) {
        synchronized(transitionMonitor) {
            deletingBots.add(botId)
            try {
                host.invalidateBot(botId)
                deletionQuiescenceFailures.remove(botId)
            } catch (failure: RuntimeException) {
                deletionQuiescenceFailures.add(botId)
                throw failure
            }
        }
    }

    /** Re-enables a bot when its durable deletion failed after local quiescence completed. */
    fun botDeletionAborted(botId: BotId) {
        synchronized(transitionMonitor) {
            if (!deletionQuiescenceFailures.remove(botId)) deletingBots.remove(botId)
        }
    }

    /** Releases transient deletion state after the durable bot and its files were handled. */
    fun botDeletionCompleted(botId: BotId) {
        synchronized(transitionMonitor) {
            deletionQuiescenceFailures.remove(botId)
            deletingBots.remove(botId)
        }
    }

    fun resetQuarantinedBinding(bindingId: UUID) {
        val binding = bindings.findById(bindingId)
            ?: throw IllegalArgumentException("Plugin binding does not exist")
        if (!binding.enabled) throw IllegalStateException("Plugin binding is disabled")
        val now = clock.instant()
        bindings.setRuntimeState(bindingId, PluginBindingRuntimeState.ACTIVE, null, now)
        host.invalidate(bindingId)
        deliveries.resumeForBinding(bindingId, now)
    }

    fun reloadPlugins() {
        synchronized(transitionMonitor) { host.reload() }
    }

    fun installArtifact(stagedArtifact: Path): PluginArtifactInstallResult = synchronized(transitionMonitor) {
        host.installArtifact(stagedArtifact)
    }

    private fun tickSafely() {
        if (!running.get() || databaseTransitionActive) return
        synchronized(transitionMonitor) {
            if (!running.get() || databaseTransitionActive) return
            if (!reconcileBindingsSafely()) return
            try {
                var count = 0
                while (count < batchSize && materializeOne()) count++
                count = 0
                while (count < batchSize && deliverOne()) count++
            } catch (exception: RuntimeException) {
                LOGGER.warn("Plugin pipeline poll failed ({})", exception.javaClass.simpleName)
            }
        }
    }

    private fun reconcileBindingsSafely(): Boolean {
        return try {
            var authoritative = bindings.findAll().toList()
                .filter { !deletingBots.contains(it.botId) }
                .filter { !mutatingBindings.contains(it.id) }
            if (botLeases != null) {
                val now = clock.instant()
                val ownership = HashMap<BotId, Boolean>()
                authoritative = authoritative.filter { binding ->
                    ownership.computeIfAbsent(binding.botId) { botId ->
                        botLeases.isOwned(botId, leaseOwner(), now)
                    }
                }
            }
            host.reconcileBindings(authoritative)
            true
        } catch (exception: RuntimeException) {
            try {
                host.invalidateAll()
                LOGGER.warn(
                    "Plugin binding reconciliation failed; local instances were invalidated ({})",
                    exception.javaClass.simpleName,
                )
            } catch (invalidationFailure: RuntimeException) {
                LOGGER.error(
                    "Plugin binding reconciliation failed and local instances could not be invalidated",
                    invalidationFailure,
                )
            }
            false
        }
    }

    private fun materializeOne(): Boolean {
        val now = clock.instant()
        val claimed = if (botLeases == null) {
            inbox.claimNext(workerId, now, leaseDuration)
        } else {
            inbox.claimNextOwned(workerId, leaseOwner(), now, leaseDuration)
        }
        val event = claimed ?: return false
        if (deletingBots.contains(event.botId)) {
            inbox.markRetry(
                event.id,
                event.fencingToken,
                now,
                now.plus(pollInterval),
                "Bot deletion is in progress on this instance",
            )
            return true
        }
        try {
            val eventBindings = bindings.findByBotId(event.botId)
                .filter { it.enabled }
                .filter { it.runtimeState.runnable() }
                .filter { !mutatingBindings.contains(it.id) }
            for (binding in eventBindings) {
                for (handlerId in host.handlerIds(binding, event.eventType)) {
                    val deliveryId = UUID.nameUUIDFromBytes(
                        "${event.id}:${binding.id}:$handlerId".toByteArray(StandardCharsets.UTF_8),
                    )
                    deliveries.createIfAbsent(deliveryId, event.id, binding.id, handlerId, now)
                }
            }
            inbox.markDispatched(event.id, event.fencingToken, clock.instant())
        } catch (exception: RuntimeException) {
            retryOrDeadLetter(event, exception)
        }
        return true
    }

    private fun deliverOne(): Boolean {
        val now = clock.instant()
        val claimed = if (botLeases == null) {
            deliveries.claimNext(workerId, now, leaseDuration)
        } else {
            deliveries.claimNextOwned(workerId, leaseOwner(), now, leaseDuration)
        }
        val delivery = claimed ?: return false
        try {
            val binding = bindings.findById(delivery.bindingId)
                ?: throw IllegalStateException("Plugin binding no longer exists")
            if (mutatingBindings.contains(binding.id)) {
                throw IllegalStateException("Plugin binding mutation is in progress on this instance")
            }
            if (deletingBots.contains(binding.botId)) {
                throw IllegalStateException("Bot deletion is in progress on this instance")
            }
            if (botLeases != null && !botLeases.isOwned(binding.botId, leaseOwner(), clock.instant())) {
                throw IllegalStateException("Bot lease is no longer owned by this instance")
            }
            val event = inbox.findById(delivery.eventId)
                ?: throw IllegalStateException("Inbox event no longer exists")
            if (!binding.enabled || !binding.runtimeState.runnable()) {
                deliveries.pauseForBinding(
                    binding.id,
                    clock.instant(),
                    binding.runtimeError ?: "Plugin binding is paused",
                )
                return true
            }
            val execution = host.executeCancellable(binding, event, delivery.handlerId)
            try {
                execution.stage().toCompletableFuture().get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                execution.cancel()
                if (!execution.await(cancellationGrace)) {
                    quarantine(binding, delivery, timeout)
                    return true
                }
                throw timeout
            }
            deliveries.markSucceeded(delivery.id, delivery.fencingToken, clock.instant())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            retryDelivery(delivery, exception)
        } catch (exception: ExecutionException) {
            retryDelivery(delivery, unwrap(exception))
        } catch (exception: TimeoutException) {
            retryDelivery(delivery, unwrap(exception))
        } catch (exception: RuntimeException) {
            retryDelivery(delivery, unwrap(exception))
        }
        return true
    }

    private fun quarantine(binding: BotPluginBinding, delivery: PluginDelivery, failure: Throwable) {
        val error = "Plugin execution timed out and did not stop within ${cancellationGrace.toMillis()} ms: " +
            safeError(failure)
        val now = clock.instant()
        host.quarantine(binding.id)
        try {
            bindings.setRuntimeState(binding.id, PluginBindingRuntimeState.QUARANTINED, error, now)
            deliveries.pauseForBinding(binding.id, now, error)
        } catch (transitionFailure: RuntimeException) {
            LOGGER.error(
                "Could not quarantine plugin binding {} after delivery {} timed out",
                binding.id,
                delivery.id,
                transitionFailure,
            )
        }
    }

    private fun retryOrDeadLetter(event: InboxEvent, failure: Throwable) {
        val now = clock.instant()
        val error = safeError(failure)
        try {
            if (event.attempt >= maxAttempts) {
                inbox.markDeadLetter(event.id, event.fencingToken, now, error)
            } else {
                inbox.markRetry(event.id, event.fencingToken, now, now.plus(backoff(event.attempt)), error)
            }
        } catch (transitionFailure: RuntimeException) {
            LOGGER.warn(
                "Could not update Inbox event {} after plugin materialization failure ({})",
                event.id,
                transitionFailure.javaClass.simpleName,
            )
        }
    }

    private fun retryDelivery(delivery: PluginDelivery, failure: Throwable) {
        val now = clock.instant()
        val error = safeError(failure)
        try {
            if (delivery.attempt >= maxAttempts) {
                deliveries.markDeadLetter(delivery.id, delivery.fencingToken, now, error)
            } else {
                deliveries.markRetry(
                    delivery.id,
                    delivery.fencingToken,
                    now,
                    now.plus(backoff(delivery.attempt)),
                    error,
                )
            }
        } catch (transitionFailure: RuntimeException) {
            LOGGER.warn(
                "Could not update plugin delivery {} after failure ({})",
                delivery.id,
                transitionFailure.javaClass.simpleName,
            )
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        polling?.cancel(false)
        synchronized(transitionMonitor) { host.close() }
    }

    private fun leaseOwner(): String = checkNotNull(instanceId) {
        "instanceId is required when bot leases are configured"
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PluginRuntimeService::class.java)

        private fun backoff(attempt: Int): Duration {
            val seconds = min(300L, 1L shl min(max(attempt - 1, 0), 8))
            return Duration.ofSeconds(seconds)
        }

        private fun unwrap(failure: Throwable): Throwable {
            var current = failure
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
                current = checkNotNull(current.cause)
            }
            return current
        }

        private fun safeError(failure: Throwable): String {
            val current = unwrap(failure)
            val message = current.message
            var value = current.javaClass.simpleName
            if (!message.isNullOrBlank()) value += ": ${message.replace('\n', ' ').replace('\r', ' ')}"
            return if (value.length > 512) value.substring(0, 512) else value
        }

        private fun positive(value: Duration, name: String): Duration {
            if (value.isZero || value.isNegative) throw IllegalArgumentException("$name must be positive")
            return value
        }
    }
}
