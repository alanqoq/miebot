package com.mieai.qqbot.gateway

import com.mieai.qqbot.client.AccessToken
import com.mieai.qqbot.client.TokenProvider
import com.mieai.qqbot.protocol.gateway.GatewayHello
import com.mieai.qqbot.protocol.gateway.GatewayOpcode
import com.mieai.qqbot.protocol.gateway.GatewayReady
import com.mieai.qqbot.protocol.json.JsonCodecs
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * One bot's testable QQ Gateway session state machine.
 *
 * Transport callbacks are serialized with an internal monitor. Listener callbacks must remain
 * short and non-blocking.
 */
class GatewaySession(
    private val config: GatewaySessionConfig,
    private val tokenProvider: TokenProvider,
    private val transport: GatewayTransport,
    private val snapshotStore: GatewaySnapshotStore,
    private val backoffStrategy: GatewayBackoffStrategy,
    private val scheduler: GatewayScheduler,
    private val listener: GatewaySessionListener,
) : AutoCloseable {
    private val monitor = Any()
    private val protocolCodec = GatewayProtocolCodec(JsonCodecs.defaultCodec())

    private var state = GatewaySessionState.NEW
    private var snapshot: GatewaySessionSnapshot? = null
    private var connection: GatewayConnection? = null
    private var currentAccessToken: AccessToken? = null
    private var heartbeatInterval: Duration? = null
    private var heartbeatTask: GatewayScheduler.Cancellable? = null
    private var ackTask: GatewayScheduler.Cancellable? = null
    private var phaseTimeoutTask: GatewayScheduler.Cancellable? = null
    private var reconnectTask: GatewayScheduler.Cancellable? = null
    private var awaitingHeartbeatAck = false
    private var heartbeatSerial = 0L
    private var generation = 0L
    private var reconnectAttempt = 0
    private var persistenceTail: CompletionStage<Void> = CompletableFuture.completedFuture(null)

    fun state(): GatewaySessionState = synchronized(monitor) { state }

    fun snapshot(): GatewaySessionSnapshot? = synchronized(monitor) { snapshot }

    /** Loads a resume snapshot and opens the initial transport connection. */
    fun start(): CompletionStage<Void> {
        val started = CompletableFuture<Void>()
        synchronized(monitor) {
            if (state != GatewaySessionState.NEW) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("Gateway session has already been started"),
                )
            }
            transitionLocked(GatewaySessionState.LOADING_SNAPSHOT)
        }

        val loading = try {
            snapshotStore.load()
        } catch (exception: RuntimeException) {
            failInitialStart(started, exception)
            return started.minimalCompletionStage()
        }
        loading.whenComplete { loaded, throwable ->
            if (throwable != null) {
                failInitialStart(started, unwrap(throwable))
                return@whenComplete
            }
            val shouldConnect = synchronized(monitor) {
                if (state == GatewaySessionState.STOPPED) {
                    started.completeExceptionally(IllegalStateException("Gateway session stopped"))
                    false
                } else {
                    snapshot = loaded
                    true
                }
            }
            if (shouldConnect) {
                connectNow(started)
            }
        }
        return started.minimalCompletionStage()
    }

    /** Stops timers and the active transport while preserving the latest resume snapshot. */
    fun stop(): CompletionStage<Void> {
        val closing: GatewayConnection?
        val pendingPersistence: CompletionStage<Void>
        synchronized(monitor) {
            if (state == GatewaySessionState.STOPPED) {
                return persistenceTail
            }
            generation++
            cancelTimersLocked()
            awaitingHeartbeatAck = false
            closing = connection
            connection = null
            pendingPersistence = persistenceTail
            transitionLocked(GatewaySessionState.STOPPED)
        }
        val closingStage = try {
            closing?.close() ?: CompletableFuture.completedFuture(null)
        } catch (exception: RuntimeException) {
            CompletableFuture.failedFuture(exception)
        }
        return closingStage.thenAcceptBoth(pendingPersistence) { _, _ -> }
    }

    override fun close() {
        stop()
    }

    private fun connectNow(initialStart: CompletableFuture<Void>?) {
        val connectionGeneration = synchronized(monitor) {
            if (state == GatewaySessionState.STOPPED) {
                initialStart?.completeExceptionally(IllegalStateException("Gateway session stopped"))
                return
            }
            cancel(reconnectTask)
            reconnectTask = null
            transitionLocked(GatewaySessionState.CONNECTING)
            ++generation
        }

        val connecting = try {
            transport.connect(config.gatewayUrl, TransportListener(connectionGeneration))
        } catch (exception: RuntimeException) {
            handleConnectFailure(connectionGeneration, exception, initialStart)
            return
        }
        connecting.whenComplete { opened, throwable ->
            if (throwable != null) {
                handleConnectFailure(connectionGeneration, unwrap(throwable), initialStart)
                return@whenComplete
            }
            if (opened == null) {
                handleConnectFailure(
                    connectionGeneration,
                    NullPointerException("transport returned a null connection"),
                    initialStart,
                )
                return@whenComplete
            }
            var phaseScheduled = false
            val stale = synchronized(monitor) {
                val current = connectionGeneration != generation || state == GatewaySessionState.STOPPED
                if (!current) {
                    connection = opened
                    transitionLocked(GatewaySessionState.AWAITING_HELLO)
                    phaseScheduled = schedulePhaseTimeoutLocked(
                        connectionGeneration,
                        GatewaySessionState.AWAITING_HELLO,
                        config.helloTimeout,
                    )
                }
                current
            }
            if (stale) {
                closeQuietly(opened)
                initialStart?.completeExceptionally(
                    IllegalStateException("Gateway connection became stale"),
                )
            } else if (initialStart != null) {
                if (phaseScheduled) {
                    initialStart.complete(null)
                } else {
                    initialStart.completeExceptionally(
                        IllegalStateException("Unable to schedule the Gateway Hello timeout"),
                    )
                }
            }
        }
    }

    private fun handleConnectFailure(
        connectionGeneration: Long,
        cause: Throwable,
        initialStart: CompletableFuture<Void>?,
    ) {
        synchronized(monitor) {
            if (connectionGeneration != generation || state == GatewaySessionState.STOPPED) {
                return
            }
            notifyFailureLocked(
                GatewaySessionException(
                    GatewayReconnectCause.TRANSPORT_FAILURE,
                    "Unable to open QQ Gateway transport",
                    cause,
                ),
            )
            beginReconnectLocked(GatewayReconnectCause.TRANSPORT_FAILURE, snapshot != null)
        }
        initialStart?.completeExceptionally(cause)
    }

    private fun handleText(connectionGeneration: Long, rawPayload: String) {
        val frame = try {
            protocolCodec.decode(rawPayload)
        } catch (exception: RuntimeException) {
            synchronized(monitor) {
                if (isCurrentLocked(connectionGeneration)) {
                    protocolFailureLocked(exception, snapshot != null)
                }
            }
            return
        }

        synchronized(monitor) {
            if (!isCurrentLocked(connectionGeneration)) {
                return
            }
            val opcode = frame.envelope.opcode()
            if (opcode != GatewayOpcode.DISPATCH && !updateSequenceLocked(frame.envelope.sequence)) {
                return
            }
            when (opcode) {
                GatewayOpcode.HELLO -> handleHelloLocked(connectionGeneration, frame.hello)
                GatewayOpcode.DISPATCH -> handleDispatchLocked(frame, rawPayload)
                GatewayOpcode.HEARTBEAT -> respondToHeartbeatRequestLocked(connectionGeneration)
                GatewayOpcode.HEARTBEAT_ACK -> acknowledgeHeartbeatLocked()
                GatewayOpcode.RECONNECT -> beginReconnectLocked(
                    GatewayReconnectCause.SERVER_RECONNECT,
                    snapshot != null,
                )

                GatewayOpcode.INVALID_SESSION -> handleInvalidSessionLocked(frame.invalidSessionResumable)
                else -> notifyUnknownOpcodeLocked(frame.envelope.op, rawPayload)
            }
        }
    }

    private fun handleHelloLocked(connectionGeneration: Long, hello: GatewayHello?) {
        if (hello == null || hello.heartbeatInterval <= 0L) {
            protocolFailureLocked(
                IllegalArgumentException("Hello heartbeat_interval must be positive"),
                snapshot != null,
            )
            return
        }
        heartbeatInterval = Duration.ofMillis(hello.heartbeatInterval)
        awaitingHeartbeatAck = false
        cancel(ackTask)
        ackTask = null
        cancel(phaseTimeoutTask)
        phaseTimeoutTask = null
        if (!scheduleNextHeartbeatLocked(connectionGeneration)) {
            return
        }

        val resumeSnapshot = snapshot
        transitionLocked(
            if (resumeSnapshot == null) GatewaySessionState.IDENTIFYING else GatewaySessionState.RESUMING,
        )
        val tokenStage = try {
            tokenProvider.getAccessToken()
        } catch (exception: RuntimeException) {
            authenticationFailureLocked(connectionGeneration, exception)
            return
        }
        tokenStage.whenComplete { token, throwable ->
            if (throwable != null) {
                synchronized(monitor) {
                    if (isCurrentLocked(connectionGeneration)) {
                        authenticationFailureLocked(connectionGeneration, unwrap(throwable))
                    }
                }
                return@whenComplete
            }
            synchronized(monitor) {
                if (!isCurrentLocked(connectionGeneration)) {
                    return@synchronized
                }
                val payload = try {
                    val acceptedToken = requireNotNull(token) { "tokenProvider returned a null token" }
                    currentAccessToken = acceptedToken
                    if (resumeSnapshot == null) {
                        protocolCodec.encodeIdentify(
                            acceptedToken,
                            config.intents,
                            config.shardSpec,
                            config.identifyProperties,
                        )
                    } else {
                        protocolCodec.encodeResume(acceptedToken, resumeSnapshot)
                    }
                } catch (exception: RuntimeException) {
                    authenticationFailureLocked(connectionGeneration, exception)
                    return@synchronized
                }
                if (!schedulePhaseTimeoutLocked(connectionGeneration, state, config.readyTimeout)) {
                    return@synchronized
                }
                sendPayloadLocked(connectionGeneration, payload)
            }
        }
    }

    private fun handleDispatchLocked(frame: GatewayProtocolCodec.DecodedFrame, rawPayload: String) {
        val sequence = frame.envelope.sequence
        val eventType = frame.envelope.eventType
        if (sequence == null || sequence < 0L || eventType.isNullOrBlank()) {
            protocolFailureLocked(
                IllegalArgumentException("Dispatch requires a non-negative sequence and event type"),
                snapshot != null,
            )
            return
        }

        if (!acceptSequenceLocked(sequence)) {
            return
        }

        if (eventType == "READY") {
            val ready = frame.ready
            if (ready == null) {
                protocolFailureLocked(
                    IllegalArgumentException("READY dispatch did not contain ready data"),
                    false,
                )
                return
            }
            snapshot = try {
                GatewaySessionSnapshot(ready.sessionId, sequence)
            } catch (exception: RuntimeException) {
                protocolFailureLocked(exception, false)
                return
            }
            reconnectAttempt = 0
            cancel(phaseTimeoutTask)
            phaseTimeoutTask = null
            transitionLocked(GatewaySessionState.READY)
            saveSnapshotLocked(snapshot!!)
            notifyReadyLocked(snapshot!!)
            return
        }

        if (eventType == "RESUMED") {
            val currentSnapshot = snapshot
            if (currentSnapshot == null) {
                protocolFailureLocked(
                    IllegalStateException("RESUMED received without a saved session"),
                    false,
                )
                return
            }
            if (!acceptSequenceLocked(sequence)) {
                return
            }
            val resumed = currentSnapshot.withSequence(sequence)
            snapshot = resumed
            reconnectAttempt = 0
            cancel(phaseTimeoutTask)
            phaseTimeoutTask = null
            transitionLocked(GatewaySessionState.READY)
            saveSnapshotLocked(resumed)
            notifyReadyLocked(resumed)
            return
        }

        val dispatch = GatewayDispatch(
            sequence,
            eventType,
            rawPayload,
            frame.envelope.eventId,
            snapshot?.sessionId,
        )
        val accepted = try {
            listener.acceptDispatch(dispatch)
        } catch (exception: RuntimeException) {
            rejectDispatchLocked(exception)
            return
        }
        if (!accepted) {
            rejectDispatchLocked(null)
            return
        }

        snapshot?.let {
            val updated = it.withSequence(sequence)
            snapshot = updated
            saveSnapshotLocked(updated)
        }
        notifyDispatchLocked(dispatch)
    }

    private fun rejectDispatchLocked(cause: RuntimeException?) {
        val failure = GatewaySessionException(
            GatewayReconnectCause.DISPATCH_REJECTED,
            "The application could not durably accept a QQ Gateway dispatch",
            cause,
        )
        notifyFailureLocked(failure)
        beginReconnectLocked(GatewayReconnectCause.DISPATCH_REJECTED, snapshot != null)
    }

    private fun handleInvalidSessionLocked(resumable: Boolean?) {
        val canResume = resumable == true && snapshot != null
        beginReconnectLocked(GatewayReconnectCause.INVALID_SESSION, canResume)
    }

    private fun updateSequenceLocked(sequence: Long?): Boolean {
        val currentSnapshot = snapshot
        if (sequence == null || currentSnapshot == null) {
            return true
        }
        if (!acceptSequenceLocked(sequence)) {
            return false
        }
        val updated = currentSnapshot.withSequence(sequence)
        if (updated !== currentSnapshot) {
            snapshot = updated
            saveSnapshotLocked(updated)
        }
        return true
    }

    private fun acceptSequenceLocked(sequence: Long): Boolean {
        if (sequence < 0L || snapshot?.let { sequence < it.sequence } == true) {
            protocolFailureLocked(
                IllegalArgumentException("Gateway sequence must not move backwards"),
                snapshot != null,
            )
            return false
        }
        return true
    }

    private fun sendScheduledHeartbeatLocked(connectionGeneration: Long) {
        if (!isCurrentLocked(connectionGeneration) || connection == null || awaitingHeartbeatAck) {
            return
        }
        sendHeartbeatFrameLocked(connectionGeneration)
    }

    private fun respondToHeartbeatRequestLocked(connectionGeneration: Long) {
        if (!isCurrentLocked(connectionGeneration) || connection == null) {
            return
        }
        sendHeartbeatFrameLocked(connectionGeneration)
    }

    private fun sendHeartbeatFrameLocked(connectionGeneration: Long) {
        val sequence = snapshot?.sequence
        val payload = try {
            protocolCodec.encodeHeartbeat(sequence)
        } catch (exception: RuntimeException) {
            protocolFailureLocked(exception, snapshot != null)
            return
        }
        awaitingHeartbeatAck = true
        val expectedSerial = ++heartbeatSerial
        cancel(ackTask)
        try {
            ackTask = scheduler.schedule(config.heartbeatAckTimeout) {
                heartbeatAckTimedOut(connectionGeneration, expectedSerial)
            }
        } catch (exception: RuntimeException) {
            schedulerFailureLocked(exception)
            return
        }
        if (sendPayloadLocked(connectionGeneration, payload)) {
            notifyHeartbeatSentLocked(sequence)
        }
    }

    private fun acknowledgeHeartbeatLocked() {
        val expected = awaitingHeartbeatAck
        awaitingHeartbeatAck = false
        cancel(ackTask)
        ackTask = null
        if (expected) {
            notifyHeartbeatAcknowledgedLocked()
        }
    }

    private fun heartbeatAckTimedOut(connectionGeneration: Long, expectedSerial: Long) {
        synchronized(monitor) {
            if (!isCurrentLocked(connectionGeneration) ||
                !awaitingHeartbeatAck ||
                heartbeatSerial != expectedSerial
            ) {
                return
            }
            beginReconnectLocked(GatewayReconnectCause.ACK_TIMEOUT, snapshot != null)
        }
    }

    private fun scheduleNextHeartbeatLocked(connectionGeneration: Long): Boolean {
        cancel(heartbeatTask)
        return try {
            heartbeatTask = scheduler.schedule(heartbeatInterval!!) {
                synchronized(monitor) {
                    if (!isCurrentLocked(connectionGeneration)) {
                        return@synchronized
                    }
                    sendScheduledHeartbeatLocked(connectionGeneration)
                    if (isCurrentLocked(connectionGeneration)) {
                        scheduleNextHeartbeatLocked(connectionGeneration)
                    }
                }
            }
            true
        } catch (exception: RuntimeException) {
            schedulerFailureLocked(exception)
            false
        }
    }

    private fun sendPayloadLocked(connectionGeneration: Long, payload: String): Boolean {
        val target = connection
        if (target == null) {
            beginReconnectLocked(GatewayReconnectCause.SEND_FAILURE, snapshot != null)
            return false
        }
        val sending = try {
            target.sendText(payload)
        } catch (exception: RuntimeException) {
            sendFailureLocked(connectionGeneration, exception)
            return false
        }
        sending.whenComplete { _, throwable ->
            if (throwable != null) {
                synchronized(monitor) {
                    if (isCurrentLocked(connectionGeneration)) {
                        sendFailureLocked(connectionGeneration, unwrap(throwable))
                    }
                }
            }
        }
        return true
    }

    private fun sendFailureLocked(connectionGeneration: Long, cause: Throwable) {
        if (!isCurrentLocked(connectionGeneration)) {
            return
        }
        notifyFailureLocked(
            GatewaySessionException(
                GatewayReconnectCause.SEND_FAILURE,
                "Unable to send a QQ Gateway frame",
                cause,
            ),
        )
        beginReconnectLocked(GatewayReconnectCause.SEND_FAILURE, snapshot != null)
    }

    private fun authenticationFailureLocked(connectionGeneration: Long, cause: Throwable) {
        if (!isCurrentLocked(connectionGeneration)) {
            return
        }
        notifyFailureLocked(
            GatewaySessionException(
                GatewayReconnectCause.AUTHENTICATION_FAILURE,
                "Unable to authenticate the QQ Gateway session",
                cause,
            ),
        )
        beginReconnectLocked(GatewayReconnectCause.AUTHENTICATION_FAILURE, snapshot != null)
    }

    private fun protocolFailureLocked(cause: Throwable, preserveSnapshot: Boolean) {
        notifyFailureLocked(
            GatewaySessionException(
                GatewayReconnectCause.PROTOCOL_FAILURE,
                "QQ Gateway payload did not match the expected protocol",
                cause,
            ),
        )
        beginReconnectLocked(GatewayReconnectCause.PROTOCOL_FAILURE, preserveSnapshot)
    }

    private fun handleClosed(connectionGeneration: Long, statusCode: Int) {
        synchronized(monitor) {
            if (!isCurrentLocked(connectionGeneration)) {
                return
            }
            if (statusCode == 4004) {
                invalidateCurrentAccessTokenLocked()
            }
            val decision = GatewayCloseClassifier.classify(statusCode, snapshot != null)
            if (decision.disposition == GatewayCloseDisposition.STOP) {
                generation++
                connection = null
                cancelTimersLocked()
                transitionLocked(GatewaySessionState.STOPPED)
                notifyStoppedLocked(decision)
                return
            }
            beginReconnectLocked(
                decision.reconnectCause,
                decision.disposition == GatewayCloseDisposition.RESUME,
            )
        }
    }

    private fun invalidateCurrentAccessTokenLocked() {
        val rejectedToken = currentAccessToken
        currentAccessToken = null
        if (rejectedToken == null) {
            return
        }
        try {
            tokenProvider.invalidate(rejectedToken)
        } catch (exception: RuntimeException) {
            notifyFailureLocked(exception)
        }
    }

    private fun handleTransportFailure(connectionGeneration: Long, cause: Throwable) {
        synchronized(monitor) {
            if (!isCurrentLocked(connectionGeneration)) {
                return
            }
            notifyFailureLocked(
                GatewaySessionException(
                    GatewayReconnectCause.TRANSPORT_FAILURE,
                    "QQ Gateway transport failed",
                    cause,
                ),
            )
            beginReconnectLocked(GatewayReconnectCause.TRANSPORT_FAILURE, snapshot != null)
        }
    }

    private fun beginReconnectLocked(cause: GatewayReconnectCause, preserveSnapshot: Boolean) {
        if (state == GatewaySessionState.STOPPED || state == GatewaySessionState.BACKING_OFF) {
            return
        }
        val closing = connection
        connection = null
        generation++
        cancelTimersLocked()
        awaitingHeartbeatAck = false
        if (!preserveSnapshot && snapshot != null) {
            snapshot = null
            clearSnapshotLocked()
        }
        transitionLocked(GatewaySessionState.BACKING_OFF)
        val attempt = ++reconnectAttempt
        val delay = try {
            backoffStrategy.delayForAttempt(attempt, cause).also {
                require(!it.isNegative) { "backoff delay must not be negative" }
            }
        } catch (exception: RuntimeException) {
            notifyFailureLocked(exception)
            transitionLocked(GatewaySessionState.STOPPED)
            closeQuietly(closing)
            return
        }
        val reconnectGeneration = generation
        try {
            reconnectTask = scheduler.schedule(delay) {
                val shouldReconnect = synchronized(monitor) {
                    state == GatewaySessionState.BACKING_OFF && generation == reconnectGeneration
                }
                if (shouldReconnect) {
                    connectNow(null)
                }
            }
        } catch (exception: RuntimeException) {
            notifyFailureLocked(
                GatewaySessionException(
                    GatewayReconnectCause.TRANSPORT_FAILURE,
                    "Unable to schedule the next QQ Gateway connection attempt",
                    exception,
                ),
            )
            transitionLocked(GatewaySessionState.STOPPED)
            closeQuietly(closing)
            return
        }
        notifyReconnectScheduledLocked(attempt, cause, delay)
        closeQuietly(closing)
    }

    private fun schedulePhaseTimeoutLocked(
        connectionGeneration: Long,
        expectedState: GatewaySessionState,
        timeout: Duration,
    ): Boolean {
        cancel(phaseTimeoutTask)
        return try {
            phaseTimeoutTask = scheduler.schedule(timeout) {
                protocolPhaseTimedOut(connectionGeneration, expectedState, timeout)
            }
            true
        } catch (exception: RuntimeException) {
            schedulerFailureLocked(exception)
            false
        }
    }

    private fun schedulerFailureLocked(cause: Throwable) {
        val closing = connection
        connection = null
        generation++
        cancelTimersLocked()
        awaitingHeartbeatAck = false
        notifyFailureLocked(
            GatewaySessionException(
                GatewayReconnectCause.TRANSPORT_FAILURE,
                "Unable to schedule QQ Gateway lifecycle work",
                cause,
            ),
        )
        transitionLocked(GatewaySessionState.STOPPED)
        closeQuietly(closing)
    }

    private fun protocolPhaseTimedOut(
        connectionGeneration: Long,
        expectedState: GatewaySessionState,
        timeout: Duration,
    ) {
        synchronized(monitor) {
            if (!isCurrentLocked(connectionGeneration) || state != expectedState) {
                return
            }
            phaseTimeoutTask = null
            val phase = when (expectedState) {
                GatewaySessionState.AWAITING_HELLO -> "Hello"
                GatewaySessionState.RESUMING -> "RESUMED"
                else -> "READY"
            }
            val timeoutException = TimeoutException(
                "Timed out waiting for QQ Gateway $phase after $timeout",
            )
            notifyFailureLocked(
                GatewaySessionException(
                    GatewayReconnectCause.PROTOCOL_FAILURE,
                    "QQ Gateway did not complete the $phase phase in time",
                    timeoutException,
                ),
            )
            val preserveSnapshot = expectedState != GatewaySessionState.IDENTIFYING && snapshot != null
            beginReconnectLocked(GatewayReconnectCause.PROTOCOL_FAILURE, preserveSnapshot)
        }
    }

    private fun saveSnapshotLocked(value: GatewaySessionSnapshot) {
        enqueuePersistenceLocked { snapshotStore.save(value) }
    }

    private fun clearSnapshotLocked() {
        enqueuePersistenceLocked(snapshotStore::clear)
    }

    private fun enqueuePersistenceLocked(operation: () -> CompletionStage<Void>) {
        persistenceTail = persistenceTail
            .handle { _, _ -> Unit }
            .thenCompose {
                try {
                    operation()
                } catch (exception: RuntimeException) {
                    CompletableFuture.failedFuture(exception)
                }
            }
        val observed = persistenceTail
        observed.whenComplete { _, throwable ->
            if (throwable != null) {
                synchronized(monitor) {
                    notifyFailureLocked(unwrap(throwable))
                }
            }
        }
    }

    private fun failInitialStart(started: CompletableFuture<Void>, cause: Throwable) {
        synchronized(monitor) {
            transitionLocked(GatewaySessionState.STOPPED)
            notifyFailureLocked(cause)
        }
        started.completeExceptionally(cause)
    }

    private fun isCurrentLocked(connectionGeneration: Long): Boolean =
        connectionGeneration == generation && state != GatewaySessionState.STOPPED

    private fun cancelTimersLocked() {
        cancel(heartbeatTask)
        cancel(ackTask)
        cancel(phaseTimeoutTask)
        cancel(reconnectTask)
        heartbeatTask = null
        ackTask = null
        phaseTimeoutTask = null
        reconnectTask = null
    }

    private fun transitionLocked(next: GatewaySessionState) {
        if (state == next) {
            return
        }
        val previous = state
        state = next
        safely { listener.onStateChanged(previous, next) }
    }

    private fun notifyReadyLocked(value: GatewaySessionSnapshot) {
        safely { listener.onReady(value) }
    }

    private fun notifyDispatchLocked(dispatch: GatewayDispatch) {
        safely { listener.onDispatch(dispatch) }
    }

    private fun notifyUnknownOpcodeLocked(opcode: Int, payload: String) {
        safely { listener.onUnknownOpcode(opcode, payload) }
    }

    private fun notifyHeartbeatSentLocked(sequence: Long?) {
        safely { listener.onHeartbeatSent(sequence) }
    }

    private fun notifyHeartbeatAcknowledgedLocked() {
        safely(listener::onHeartbeatAcknowledged)
    }

    private fun notifyReconnectScheduledLocked(
        attempt: Int,
        cause: GatewayReconnectCause,
        delay: Duration,
    ) {
        safely { listener.onReconnectScheduled(attempt, cause, delay) }
    }

    private fun notifyFailureLocked(cause: Throwable) {
        safely { listener.onFailure(cause) }
    }

    private fun notifyStoppedLocked(decision: GatewayCloseDecision) {
        safely { listener.onStopped(decision) }
    }

    private inner class TransportListener(
        private val connectionGeneration: Long,
    ) : GatewayTransport.Listener {
        override fun onText(payload: String) {
            handleText(connectionGeneration, payload)
        }

        override fun onClosed(statusCode: Int, reason: String) {
            handleClosed(connectionGeneration, statusCode)
        }

        override fun onFailure(cause: Throwable) {
            handleTransportFailure(connectionGeneration, cause)
        }
    }

    companion object {
        private fun safely(callback: () -> Unit) {
            try {
                callback()
            } catch (_: RuntimeException) {
                // Observer failures must not corrupt the connection state machine.
            }
        }

        private fun cancel(task: GatewayScheduler.Cancellable?) {
            task?.cancel()
        }

        private fun closeQuietly(connection: GatewayConnection?) {
            if (connection == null) {
                return
            }
            try {
                connection.close().exceptionally { null }
            } catch (_: RuntimeException) {
                // The session has already fenced this connection generation.
            }
        }

        private fun unwrap(throwable: Throwable): Throwable {
            var current = throwable
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
                current = current.cause!!
            }
            return current
        }
    }
}
