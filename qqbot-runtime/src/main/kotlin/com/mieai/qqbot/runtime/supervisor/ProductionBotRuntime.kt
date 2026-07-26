package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.client.BotCredentials
import com.mieai.qqbot.client.GatewayBotInfo
import com.mieai.qqbot.client.QqClientException
import com.mieai.qqbot.client.QqClientFailure
import com.mieai.qqbot.client.TokenProvider
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.gateway.GatewayBackoffStrategy
import com.mieai.qqbot.gateway.GatewayCloseDecision
import com.mieai.qqbot.gateway.GatewayDispatch
import com.mieai.qqbot.gateway.GatewayReconnectCause
import com.mieai.qqbot.gateway.GatewayScheduler
import com.mieai.qqbot.gateway.GatewaySession
import com.mieai.qqbot.gateway.GatewaySessionConfig
import com.mieai.qqbot.gateway.GatewaySessionException
import com.mieai.qqbot.gateway.GatewaySessionListener
import com.mieai.qqbot.gateway.GatewaySessionSnapshot
import com.mieai.qqbot.gateway.GatewaySessionState
import com.mieai.qqbot.gateway.GatewaySnapshotStore
import com.mieai.qqbot.gateway.GatewayTransport
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/** One production bot runtime from OpenAPI discovery through the Gateway session lifecycle. */
class ProductionBotRuntime(
    private val definition: BotDefinition,
    private val credentials: BotCredentials,
    private val discovery: GatewayDiscovery,
    private val tokenProvider: TokenProvider,
    private val gatewayTransport: GatewayTransport,
    private val snapshotStore: GatewaySnapshotStore,
    private val scheduler: RuntimeScheduler,
    private val backoffStrategy: GatewayBackoffStrategy,
    private val observer: BotRuntimeObserver,
) : ManagedBotRuntime {
    private val monitor = Any()
    private var lifecycle = Lifecycle.NEW
    private var generation = 0L
    private var retryAttempt = 0
    private var gatewaySession: GatewaySession? = null
    private var discoveryRetry: GatewayScheduler.Cancellable? = null
    private var initialStart: CompletableFuture<Void>? = null
    private var stopResult: CompletableFuture<Void>? = null

    override fun start(): CompletionStage<Void> {
        val result: CompletableFuture<Void>
        val currentGeneration: Long
        synchronized(monitor) {
            if (lifecycle != Lifecycle.NEW) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("The bot runtime has already been started"),
                )
            }
            lifecycle = Lifecycle.RUNNING
            generation++
            currentGeneration = generation
            result = CompletableFuture()
            initialStart = result
        }
        publishState(BotRuntimeState.STARTING)
        discover(currentGeneration)
        return result.minimalCompletionStage()
    }

    override fun stop(): CompletionStage<Void> {
        val stoppingSession: GatewaySession?
        val retry: GatewayScheduler.Cancellable?
        val pendingStart: CompletableFuture<Void>?
        val result: CompletableFuture<Void>
        synchronized(monitor) {
            stopResult?.let { return it.minimalCompletionStage() }
            result = CompletableFuture()
            stopResult = result
            if (lifecycle == Lifecycle.STOPPED) {
                result.complete(null)
                return result.minimalCompletionStage()
            }
            lifecycle = Lifecycle.STOPPING
            generation++
            stoppingSession = gatewaySession
            gatewaySession = null
            retry = discoveryRetry
            discoveryRetry = null
            pendingStart = initialStart
        }

        publishState(BotRuntimeState.STOPPING)
        cancel(retry)
        val stopping = stopSession(stoppingSession)
        var localCleanupFailed = false
        try {
            scheduler.close()
        } catch (_: RuntimeException) {
            localCleanupFailed = true
        }
        try {
            credentials.close()
        } catch (_: RuntimeException) {
            localCleanupFailed = true
        }
        pendingStart?.completeExceptionally(
            IllegalStateException("The bot runtime was stopped during its initial connection attempt"),
        )
        val cleanupFailed = localCleanupFailed
        stopping.whenComplete { _, failure ->
            synchronized(monitor) { lifecycle = Lifecycle.STOPPED }
            publishState(BotRuntimeState.STOPPED)
            if (failure != null || cleanupFailed) {
                result.completeExceptionally(
                    IllegalStateException("The bot runtime could not fully release its resources"),
                )
            } else {
                result.complete(null)
            }
        }
        return result.minimalCompletionStage()
    }

    private fun discover(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration)) return
        publishState(BotRuntimeState.DISCOVERING)
        val discovering = try {
            discovery.discover()
        } catch (exception: RuntimeException) {
            handleDiscoveryFailure(expectedGeneration, exception)
            return
        }
        discovering.whenComplete { gateway, failure ->
            if (failure != null) {
                handleDiscoveryFailure(expectedGeneration, unwrap(failure))
                return@whenComplete
            }
            if (gateway == null) {
                handleDiscoveryFailure(
                    expectedGeneration,
                    NullPointerException("discovery returned a null Gateway response"),
                )
                return@whenComplete
            }
            try {
                handleDiscoverySuccess(expectedGeneration, gateway)
            } catch (exception: RuntimeException) {
                handleDiscoveryFailure(expectedGeneration, exception)
            }
        }
    }

    private fun handleDiscoverySuccess(expectedGeneration: Long, gateway: GatewayBotInfo) {
        if (!isCurrent(expectedGeneration)) return
        if (definition.shardSpec.count > gateway.recommendedShardCount) {
            failTerminal(SHARD_CONFIGURATION_INVALID)
            completeInitialFailure()
            return
        }
        if (gateway.sessionStartLimit.remaining == 0) {
            publishFailure(SESSION_LIMITED)
            scheduleDiscoveryRetry(
                expectedGeneration,
                normalizedLimitDelay(gateway.sessionStartLimit.resetAfter),
            )
            completeInitialFailure()
            return
        }

        val created = GatewaySession(
            GatewaySessionConfig.defaults(gateway.url, definition.intents, definition.shardSpec),
            tokenProvider,
            gatewayTransport,
            snapshotStore,
            backoffStrategy,
            scheduler,
            SessionObserver(expectedGeneration),
        )
        synchronized(monitor) {
            if (!acceptsLocked(expectedGeneration)) {
                created.stop()
                return
            }
            discoveryRetry = null
            gatewaySession = created
        }

        val starting = try {
            created.start()
        } catch (_: RuntimeException) {
            completeInitialFailure()
            handleSessionStartFailure(expectedGeneration, created)
            return
        }
        starting.whenComplete { _, failure ->
            if (failure == null) {
                completeInitialSuccess()
            } else {
                completeInitialFailure()
                handleSessionStartFailure(expectedGeneration, created)
            }
        }
    }

    private fun handleSessionStartFailure(
        expectedGeneration: Long,
        failedSession: GatewaySession,
    ) {
        if (!isCurrentSession(expectedGeneration, failedSession)) return
        if (failedSession.state() != GatewaySessionState.STOPPED) return
        synchronized(monitor) {
            if (!acceptsLocked(expectedGeneration) || gatewaySession !== failedSession) return
            gatewaySession = null
        }
        failedSession.stop()
        scheduleDiscoveryRetry(expectedGeneration, null)
    }

    private fun handleDiscoveryFailure(expectedGeneration: Long, cause: Throwable) {
        if (!isCurrent(expectedGeneration)) return
        val failure = mapDiscoveryFailure(cause)
        publishFailure(failure)
        completeInitialFailure()
        if (failure.retryable) {
            scheduleDiscoveryRetry(expectedGeneration, null)
        } else {
            publishState(BotRuntimeState.FAILED)
        }
    }

    private fun scheduleDiscoveryRetry(expectedGeneration: Long, requestedDelay: Duration?) {
        val attempt: Int
        synchronized(monitor) {
            if (!acceptsLocked(expectedGeneration)) return
            retryAttempt++
            attempt = retryAttempt
        }
        val delay = try {
            val backoff = backoffStrategy.delayForAttempt(
                attempt,
                GatewayReconnectCause.TRANSPORT_FAILURE,
            )
            require(!backoff.isNegative) { "backoff delay must not be negative" }
            if (requestedDelay == null || requestedDelay < backoff) backoff else requestedDelay
        } catch (_: RuntimeException) {
            failTerminal(RETRY_SCHEDULING_FAILED)
            return
        }

        val scheduled = try {
            scheduler.schedule(
                delay,
                retry@{
                    synchronized(monitor) {
                        if (!acceptsLocked(expectedGeneration)) return@retry
                        discoveryRetry = null
                    }
                    discover(expectedGeneration)
                },
            )
        } catch (_: RuntimeException) {
            failTerminal(RETRY_SCHEDULING_FAILED)
            return
        }
        synchronized(monitor) {
            if (!acceptsLocked(expectedGeneration)) {
                scheduled.cancel()
                return
            }
            cancel(discoveryRetry)
            discoveryRetry = scheduled
        }
        publishState(BotRuntimeState.RECONNECTING)
        safely { observer.onReconnectScheduled() }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        synchronized(monitor) { acceptsLocked(expectedGeneration) }

    private fun isCurrentSession(
        expectedGeneration: Long,
        expectedSession: GatewaySession,
    ): Boolean = synchronized(monitor) {
        acceptsLocked(expectedGeneration) && gatewaySession === expectedSession
    }

    private fun acceptsLocked(expectedGeneration: Long): Boolean =
        lifecycle == Lifecycle.RUNNING && generation == expectedGeneration

    private fun completeInitialSuccess() {
        val result = synchronized(monitor) { initialStart }
        result?.complete(null)
    }

    private fun completeInitialFailure() {
        val result = synchronized(monitor) { initialStart }
        result?.completeExceptionally(
            IllegalStateException("The bot runtime did not complete its initial connection attempt"),
        )
    }

    private fun failTerminal(failure: BotRuntimeFailure) {
        publishFailure(failure)
        publishState(BotRuntimeState.FAILED)
    }

    private fun publishState(state: BotRuntimeState) {
        safely { observer.onStateChanged(state) }
    }

    private fun publishFailure(failure: BotRuntimeFailure) {
        safely { observer.onFailure(failure) }
    }

    private inner class SessionObserver(
        private val expectedGeneration: Long,
    ) : GatewaySessionListener {
        private var heartbeatSequence: Long? = null

        override fun onStateChanged(previous: GatewaySessionState, current: GatewaySessionState) {
            if (isCurrent(expectedGeneration)) publishState(mapState(current))
        }

        override fun onReady(snapshot: GatewaySessionSnapshot) {
            if (!isCurrent(expectedGeneration)) return
            synchronized(monitor) { retryAttempt = 0 }
            safely { observer.onReady(BotSessionSnapshot(snapshot.sessionId, snapshot.sequence)) }
        }

        override fun acceptDispatch(dispatch: GatewayDispatch): Boolean {
            if (!isCurrent(expectedGeneration)) {
                // Reject a final callback racing a stop/rebuild so its sequence is not acknowledged.
                return false
            }
            return observer.acceptDispatch(dispatch)
        }

        override fun onDispatch(dispatch: GatewayDispatch) {
            if (isCurrent(expectedGeneration)) safely { observer.onDispatch(dispatch) }
        }

        override fun onHeartbeatSent(sequence: Long?) {
            heartbeatSequence = sequence
        }

        override fun onHeartbeatAcknowledged() {
            if (!isCurrent(expectedGeneration)) return
            safely { observer.onHeartbeatAcknowledged(heartbeatSequence) }
        }

        override fun onReconnectScheduled(
            attempt: Int,
            cause: GatewayReconnectCause,
            delay: Duration,
        ) {
            if (isCurrent(expectedGeneration)) safely { observer.onReconnectScheduled() }
        }

        override fun onFailure(cause: Throwable) {
            if (isCurrent(expectedGeneration)) publishFailure(mapGatewayFailure(cause))
        }

        override fun onStopped(decision: GatewayCloseDecision) {
            if (!isCurrent(expectedGeneration)) return
            publishFailure(mapStopped(decision))
            publishState(BotRuntimeState.FAILED)
        }
    }

    private enum class Lifecycle {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
    }

    private companion object {
        val SESSION_LIMITED = BotRuntimeFailure(
            "GATEWAY_SESSION_LIMITED",
            "QQ has temporarily exhausted this bot's Gateway session creation allowance",
            true,
        )
        val SHARD_CONFIGURATION_INVALID = BotRuntimeFailure(
            "GATEWAY_SHARD_CONFIGURATION_INVALID",
            "The configured shard count exceeds the count recommended by QQ",
            false,
        )
        val RETRY_SCHEDULING_FAILED = BotRuntimeFailure(
            "GATEWAY_RETRY_SCHEDULING_FAILED",
            "The bot runtime could not schedule its next connection attempt",
            false,
        )

        fun stopSession(session: GatewaySession?): CompletionStage<Void> {
            if (session == null) return CompletableFuture.completedFuture<Void>(null)
            return try {
                session.stop()
            } catch (exception: RuntimeException) {
                CompletableFuture.failedFuture(exception)
            }
        }

        fun normalizedLimitDelay(resetAfter: Duration): Duration {
            return if (resetAfter.isZero) Duration.ofSeconds(1) else resetAfter
        }

        fun mapDiscoveryFailure(cause: Throwable): BotRuntimeFailure {
            if (cause is QqClientException) {
                return when (cause.failure) {
                    QqClientFailure.AUTHENTICATION -> BotRuntimeFailure(
                        "QQ_AUTHENTICATION_REJECTED",
                        "QQ rejected the bot credentials",
                        false,
                    )
                    QqClientFailure.TIMEOUT -> BotRuntimeFailure(
                        "QQ_REQUEST_TIMEOUT",
                        "QQ did not answer the Gateway discovery request in time",
                        true,
                    )
                    QqClientFailure.TRANSPORT -> BotRuntimeFailure(
                        "QQ_TRANSPORT_UNAVAILABLE",
                        "QQ Gateway discovery is temporarily unreachable",
                        true,
                    )
                    QqClientFailure.PROTOCOL -> BotRuntimeFailure(
                        "QQ_PROTOCOL_ERROR",
                        "QQ returned an invalid Gateway discovery response",
                        false,
                    )
                    QqClientFailure.HTTP_STATUS -> mapHttpFailure(cause)
                }
            }
            return BotRuntimeFailure(
                "GATEWAY_DISCOVERY_FAILED",
                "Gateway discovery could not be completed",
                true,
            )
        }

        fun mapHttpFailure(exception: QqClientException): BotRuntimeFailure {
            val status = exception.httpStatus ?: -1
            return when {
                status == 401 || status == 403 -> BotRuntimeFailure(
                    "QQ_AUTHENTICATION_REJECTED",
                    "QQ rejected the bot credentials",
                    false,
                )
                status == 429 -> BotRuntimeFailure(
                    "QQ_RATE_LIMITED",
                    "QQ temporarily rate limited Gateway discovery",
                    true,
                )
                status >= 500 -> BotRuntimeFailure(
                    "QQ_SERVICE_UNAVAILABLE",
                    "QQ Gateway discovery is temporarily unavailable",
                    true,
                )
                else -> BotRuntimeFailure(
                    "QQ_REQUEST_REJECTED",
                    "QQ rejected the Gateway discovery request",
                    false,
                )
            }
        }

        fun mapGatewayFailure(cause: Throwable): BotRuntimeFailure {
            if (cause is GatewaySessionException) {
                val category = cause.category()
                return BotRuntimeFailure(
                    "GATEWAY_${category.name}",
                    gatewayFailureMessage(category),
                    true,
                )
            }
            return BotRuntimeFailure(
                "GATEWAY_RUNTIME_FAILURE",
                "The QQ Gateway session encountered an internal failure",
                true,
            )
        }

        fun gatewayFailureMessage(cause: GatewayReconnectCause): String = when (cause) {
            GatewayReconnectCause.SERVER_RECONNECT -> "QQ requested a Gateway reconnect"
            GatewayReconnectCause.INVALID_SESSION -> "QQ rejected the resumable Gateway session"
            GatewayReconnectCause.ACK_TIMEOUT -> "QQ did not acknowledge a Gateway heartbeat in time"
            GatewayReconnectCause.CONNECTION_CLOSED -> "The QQ Gateway connection was closed"
            GatewayReconnectCause.RATE_LIMITED -> "QQ rate limited the Gateway connection"
            GatewayReconnectCause.AUTHENTICATION_FAILURE -> "The QQ Gateway authentication attempt failed"
            GatewayReconnectCause.TRANSPORT_FAILURE -> "The QQ Gateway transport failed"
            GatewayReconnectCause.PROTOCOL_FAILURE -> "The QQ Gateway returned an invalid payload"
            GatewayReconnectCause.SEND_FAILURE -> "A QQ Gateway frame could not be sent"
            GatewayReconnectCause.DISPATCH_REJECTED -> "The application could not persist a QQ Gateway event"
        }

        fun mapStopped(decision: GatewayCloseDecision): BotRuntimeFailure =
            when (decision.statusCode) {
                4010, 4011 -> BotRuntimeFailure(
                    "GATEWAY_SHARD_REJECTED",
                    "QQ rejected the configured Gateway shard",
                    false,
                )
                4013, 4014 -> BotRuntimeFailure(
                    "GATEWAY_INTENTS_REJECTED",
                    "QQ rejected the configured Gateway event permissions",
                    false,
                )
                4914 -> BotRuntimeFailure(
                    "QQ_BOT_OFFLINE",
                    "The QQ bot is not available in this environment",
                    false,
                )
                4915 -> BotRuntimeFailure(
                    "QQ_BOT_BANNED",
                    "The QQ bot is currently prohibited from connecting",
                    false,
                )
                else -> BotRuntimeFailure(
                    "GATEWAY_STOPPED",
                    "QQ permanently rejected the Gateway connection",
                    false,
                )
            }

        fun mapState(state: GatewaySessionState): BotRuntimeState = when (state) {
            GatewaySessionState.NEW -> BotRuntimeState.STARTING
            GatewaySessionState.LOADING_SNAPSHOT,
            GatewaySessionState.CONNECTING,
            GatewaySessionState.AWAITING_HELLO,
            -> BotRuntimeState.CONNECTING
            GatewaySessionState.IDENTIFYING,
            GatewaySessionState.RESUMING,
            -> BotRuntimeState.AUTHENTICATING
            GatewaySessionState.READY -> BotRuntimeState.ONLINE
            GatewaySessionState.BACKING_OFF -> BotRuntimeState.RECONNECTING
            GatewaySessionState.STOPPED -> BotRuntimeState.STOPPED
        }

        fun unwrap(throwable: Throwable): Throwable {
            var current = throwable
            while ((current is CompletionException || current is ExecutionException) &&
                current.cause != null
            ) {
                current = current.cause!!
            }
            return current
        }

        fun safely(callback: () -> Unit) {
            try {
                callback()
            } catch (_: RuntimeException) {
                // Runtime observers are diagnostic and must not corrupt a live Gateway session.
            }
        }

        fun cancel(task: GatewayScheduler.Cancellable?) {
            task?.cancel()
        }
    }
}
