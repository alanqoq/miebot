package com.mieai.qqbot.gateway;

import com.mieai.qqbot.client.AccessToken;
import com.mieai.qqbot.client.TokenProvider;
import com.mieai.qqbot.protocol.gateway.GatewayHello;
import com.mieai.qqbot.protocol.gateway.GatewayOpcode;
import com.mieai.qqbot.protocol.gateway.GatewayReady;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * One bot's testable QQ Gateway session state machine.
 *
 * <p>The class serializes transport callbacks with an internal monitor. Listener callbacks must be
 * short and non-blocking.
 */
public final class GatewaySession implements AutoCloseable {
    private final Object monitor = new Object();
    private final GatewaySessionConfig config;
    private final TokenProvider tokenProvider;
    private final GatewayTransport transport;
    private final GatewaySnapshotStore snapshotStore;
    private final GatewayBackoffStrategy backoffStrategy;
    private final GatewayScheduler scheduler;
    private final GatewaySessionListener listener;
    private final GatewayProtocolCodec protocolCodec;

    private GatewaySessionState state = GatewaySessionState.NEW;
    private GatewaySessionSnapshot snapshot;
    private GatewayConnection connection;
    private AccessToken currentAccessToken;
    private Duration heartbeatInterval;
    private GatewayScheduler.Cancellable heartbeatTask;
    private GatewayScheduler.Cancellable ackTask;
    private GatewayScheduler.Cancellable phaseTimeoutTask;
    private GatewayScheduler.Cancellable reconnectTask;
    private boolean awaitingHeartbeatAck;
    private long heartbeatSerial;
    private long generation;
    private int reconnectAttempt;
    private CompletionStage<Void> persistenceTail = CompletableFuture.completedFuture(null);

    public GatewaySession(
            GatewaySessionConfig config,
            TokenProvider tokenProvider,
            GatewayTransport transport,
            GatewaySnapshotStore snapshotStore,
            GatewayBackoffStrategy backoffStrategy,
            GatewayScheduler scheduler,
            GatewaySessionListener listener) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.snapshotStore =
                Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.backoffStrategy =
                Objects.requireNonNull(backoffStrategy, "backoffStrategy must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        protocolCodec = new GatewayProtocolCodec(JsonCodecs.defaultCodec());
    }

    public GatewaySessionState state() {
        synchronized (monitor) {
            return state;
        }
    }

    public Optional<GatewaySessionSnapshot> snapshot() {
        synchronized (monitor) {
            return Optional.ofNullable(snapshot);
        }
    }

    /** Loads a resume snapshot and opens the initial transport connection. */
    public CompletionStage<Void> start() {
        CompletableFuture<Void> started = new CompletableFuture<>();
        synchronized (monitor) {
            if (state != GatewaySessionState.NEW) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Gateway session has already been started"));
            }
            transitionLocked(GatewaySessionState.LOADING_SNAPSHOT);
        }

        CompletionStage<Optional<GatewaySessionSnapshot>> loading;
        try {
            loading = Objects.requireNonNull(snapshotStore.load(), "snapshotStore.load returned null");
        } catch (RuntimeException exception) {
            failInitialStart(started, exception);
            return started.minimalCompletionStage();
        }
        loading.whenComplete((loaded, throwable) -> {
            if (throwable != null) {
                failInitialStart(started, unwrap(throwable));
                return;
            }
            synchronized (monitor) {
                if (state == GatewaySessionState.STOPPED) {
                    started.completeExceptionally(new IllegalStateException("Gateway session stopped"));
                    return;
                }
                snapshot = Objects.requireNonNull(loaded, "snapshotStore returned null").orElse(null);
            }
            connectNow(started);
        });
        return started.minimalCompletionStage();
    }

    /** Stops timers and the active transport while preserving the latest resume snapshot. */
    public CompletionStage<Void> stop() {
        GatewayConnection closing;
        CompletionStage<Void> pendingPersistence;
        synchronized (monitor) {
            if (state == GatewaySessionState.STOPPED) {
                return persistenceTail;
            }
            generation++;
            cancelTimersLocked();
            awaitingHeartbeatAck = false;
            closing = connection;
            connection = null;
            pendingPersistence = persistenceTail;
            transitionLocked(GatewaySessionState.STOPPED);
        }
        CompletionStage<Void> closingStage;
        try {
            closingStage = closing == null
                    ? CompletableFuture.completedFuture(null)
                    : Objects.requireNonNull(closing.close(), "connection.close returned null");
        } catch (RuntimeException exception) {
            closingStage = CompletableFuture.failedFuture(exception);
        }
        return closingStage.thenCombine(pendingPersistence, (ignoredClose, ignoredPersistence) -> null);
    }

    @Override
    public void close() {
        stop();
    }

    private void connectNow(CompletableFuture<Void> initialStart) {
        long connectionGeneration;
        synchronized (monitor) {
            if (state == GatewaySessionState.STOPPED) {
                if (initialStart != null) {
                    initialStart.completeExceptionally(new IllegalStateException("Gateway session stopped"));
                }
                return;
            }
            cancel(reconnectTask);
            reconnectTask = null;
            transitionLocked(GatewaySessionState.CONNECTING);
            connectionGeneration = ++generation;
        }

        CompletionStage<GatewayConnection> connecting;
        try {
            connecting = Objects.requireNonNull(
                    transport.connect(
                            config.gatewayUrl(), new TransportListener(connectionGeneration)),
                    "transport.connect returned null");
        } catch (RuntimeException exception) {
            handleConnectFailure(connectionGeneration, exception, initialStart);
            return;
        }
        connecting.whenComplete((opened, throwable) -> {
            if (throwable != null) {
                handleConnectFailure(connectionGeneration, unwrap(throwable), initialStart);
                return;
            }
            if (opened == null) {
                handleConnectFailure(
                        connectionGeneration,
                        new NullPointerException("transport returned a null connection"),
                        initialStart);
                return;
            }
            boolean stale;
            boolean phaseScheduled = false;
            synchronized (monitor) {
                stale = connectionGeneration != generation || state == GatewaySessionState.STOPPED;
                if (!stale) {
                    connection = opened;
                    transitionLocked(GatewaySessionState.AWAITING_HELLO);
                    phaseScheduled = schedulePhaseTimeoutLocked(
                            connectionGeneration,
                            GatewaySessionState.AWAITING_HELLO,
                            config.helloTimeout());
                }
            }
            if (stale) {
                closeQuietly(opened);
                if (initialStart != null) {
                    initialStart.completeExceptionally(new IllegalStateException("Gateway connection became stale"));
                }
            } else if (initialStart != null) {
                if (phaseScheduled) {
                    initialStart.complete(null);
                } else {
                    initialStart.completeExceptionally(
                            new IllegalStateException("Unable to schedule the Gateway Hello timeout"));
                }
            }
        });
    }

    private void handleConnectFailure(
            long connectionGeneration, Throwable cause, CompletableFuture<Void> initialStart) {
        synchronized (monitor) {
            if (connectionGeneration != generation || state == GatewaySessionState.STOPPED) {
                return;
            }
            notifyFailureLocked(new GatewaySessionException(
                    GatewayReconnectCause.TRANSPORT_FAILURE,
                    "Unable to open QQ Gateway transport",
                    cause));
            beginReconnectLocked(GatewayReconnectCause.TRANSPORT_FAILURE, snapshot != null);
        }
        if (initialStart != null) {
            initialStart.completeExceptionally(cause);
        }
    }

    private void handleText(long connectionGeneration, String rawPayload) {
        GatewayProtocolCodec.DecodedFrame frame;
        try {
            frame = protocolCodec.decode(rawPayload);
        } catch (RuntimeException exception) {
            synchronized (monitor) {
                if (isCurrentLocked(connectionGeneration)) {
                    protocolFailureLocked(exception, snapshot != null);
                }
            }
            return;
        }

        synchronized (monitor) {
            if (!isCurrentLocked(connectionGeneration)) {
                return;
            }
            GatewayOpcode opcode = frame.envelope().opcode();
            if (opcode != GatewayOpcode.DISPATCH
                    && !updateSequenceLocked(frame.envelope().sequence())) {
                return;
            }
            switch (opcode) {
                case HELLO -> handleHelloLocked(connectionGeneration, frame.hello());
                case DISPATCH -> handleDispatchLocked(frame, rawPayload);
                case HEARTBEAT -> respondToHeartbeatRequestLocked(connectionGeneration);
                case HEARTBEAT_ACK -> acknowledgeHeartbeatLocked();
                case RECONNECT -> beginReconnectLocked(
                        GatewayReconnectCause.SERVER_RECONNECT, snapshot != null);
                case INVALID_SESSION -> handleInvalidSessionLocked(frame.invalidSessionResumable());
                case UNKNOWN -> notifyUnknownOpcodeLocked(frame.envelope().op(), rawPayload);
                default -> notifyUnknownOpcodeLocked(frame.envelope().op(), rawPayload);
            }
        }
    }

    private void handleHelloLocked(long connectionGeneration, GatewayHello hello) {
        if (hello == null || hello.heartbeatInterval() <= 0L) {
            protocolFailureLocked(
                    new IllegalArgumentException("Hello heartbeat_interval must be positive"),
                    snapshot != null);
            return;
        }
        heartbeatInterval = Duration.ofMillis(hello.heartbeatInterval());
        awaitingHeartbeatAck = false;
        cancel(ackTask);
        ackTask = null;
        cancel(phaseTimeoutTask);
        phaseTimeoutTask = null;
        if (!scheduleNextHeartbeatLocked(connectionGeneration)) {
            return;
        }

        GatewaySessionSnapshot resumeSnapshot = snapshot;
        transitionLocked(resumeSnapshot == null
                ? GatewaySessionState.IDENTIFYING
                : GatewaySessionState.RESUMING);
        CompletionStage<AccessToken> tokenStage;
        try {
            tokenStage = Objects.requireNonNull(
                    tokenProvider.getAccessToken(), "tokenProvider returned null");
        } catch (RuntimeException exception) {
            authenticationFailureLocked(connectionGeneration, exception);
            return;
        }
        tokenStage.whenComplete((token, throwable) -> {
            if (throwable != null) {
                synchronized (monitor) {
                    if (isCurrentLocked(connectionGeneration)) {
                        authenticationFailureLocked(connectionGeneration, unwrap(throwable));
                    }
                }
                return;
            }
            synchronized (monitor) {
                if (!isCurrentLocked(connectionGeneration)) {
                    return;
                }
                String payload;
                try {
                    AccessToken acceptedToken = Objects.requireNonNull(
                            token, "tokenProvider returned a null token");
                    currentAccessToken = acceptedToken;
                    payload = resumeSnapshot == null
                            ? protocolCodec.encodeIdentify(
                                    acceptedToken,
                                    config.intents(),
                                    config.shardSpec(),
                                    config.identifyProperties())
                            : protocolCodec.encodeResume(acceptedToken, resumeSnapshot);
                } catch (RuntimeException exception) {
                    authenticationFailureLocked(connectionGeneration, exception);
                    return;
                }
                if (!schedulePhaseTimeoutLocked(
                        connectionGeneration, state, config.readyTimeout())) {
                    return;
                }
                sendPayloadLocked(connectionGeneration, payload);
            }
        });
    }

    private void handleDispatchLocked(
            GatewayProtocolCodec.DecodedFrame frame, String rawPayload) {
        Long sequence = frame.envelope().sequence();
        String eventType = frame.envelope().eventType();
        if (sequence == null || sequence < 0L || eventType == null || eventType.isBlank()) {
            protocolFailureLocked(
                    new IllegalArgumentException("Dispatch requires a non-negative sequence and event type"),
                    snapshot != null);
            return;
        }

        // Validate the incoming sequence before invoking the application. The application may
        // reject persistence, in which case the old snapshot must remain available for Resume.
        if (!acceptSequenceLocked(sequence)) {
            return;
        }

        if ("READY".equals(eventType)) {
            GatewayReady ready = frame.ready();
            if (ready == null) {
                protocolFailureLocked(
                        new IllegalArgumentException("READY dispatch did not contain ready data"),
                        false);
                return;
            }
            try {
                snapshot = new GatewaySessionSnapshot(ready.sessionId(), sequence);
            } catch (RuntimeException exception) {
                protocolFailureLocked(exception, false);
                return;
            }
            reconnectAttempt = 0;
            cancel(phaseTimeoutTask);
            phaseTimeoutTask = null;
            transitionLocked(GatewaySessionState.READY);
            saveSnapshotLocked(snapshot);
            notifyReadyLocked(snapshot);
            return;
        }

        if ("RESUMED".equals(eventType)) {
            if (snapshot == null) {
                protocolFailureLocked(
                        new IllegalStateException("RESUMED received without a saved session"),
                        false);
                return;
            }
            if (!acceptSequenceLocked(sequence)) {
                return;
            }
            snapshot = snapshot.withSequence(sequence);
            reconnectAttempt = 0;
            cancel(phaseTimeoutTask);
            phaseTimeoutTask = null;
            transitionLocked(GatewaySessionState.READY);
            saveSnapshotLocked(snapshot);
            notifyReadyLocked(snapshot);
            return;
        }

        GatewayDispatch dispatch = new GatewayDispatch(
                sequence,
                eventType,
                rawPayload,
                frame.envelope().eventId(),
                snapshot == null ? null : snapshot.sessionId());
        boolean accepted;
        try {
            accepted = listener.acceptDispatch(dispatch);
        } catch (RuntimeException exception) {
            rejectDispatchLocked(exception);
            return;
        }
        if (!accepted) {
            rejectDispatchLocked(null);
            return;
        }

        if (snapshot != null) {
            snapshot = snapshot.withSequence(sequence);
            saveSnapshotLocked(snapshot);
        }
        notifyDispatchLocked(dispatch);
    }

    private void rejectDispatchLocked(RuntimeException cause) {
        GatewaySessionException failure = new GatewaySessionException(
                GatewayReconnectCause.DISPATCH_REJECTED,
                "The application could not durably accept a QQ Gateway dispatch",
                cause);
        notifyFailureLocked(failure);
        beginReconnectLocked(GatewayReconnectCause.DISPATCH_REJECTED, snapshot != null);
    }

    private void handleInvalidSessionLocked(Boolean resumable) {
        boolean canResume = Boolean.TRUE.equals(resumable) && snapshot != null;
        beginReconnectLocked(GatewayReconnectCause.INVALID_SESSION, canResume);
    }

    private boolean updateSequenceLocked(Long sequence) {
        if (sequence == null || snapshot == null) {
            return true;
        }
        if (!acceptSequenceLocked(sequence)) {
            return false;
        }
        GatewaySessionSnapshot updated = snapshot.withSequence(sequence);
        if (updated != snapshot) {
            snapshot = updated;
            saveSnapshotLocked(updated);
        }
        return true;
    }

    private boolean acceptSequenceLocked(long sequence) {
        if (sequence < 0L || (snapshot != null && sequence < snapshot.sequence())) {
            protocolFailureLocked(
                    new IllegalArgumentException("Gateway sequence must not move backwards"),
                    snapshot != null);
            return false;
        }
        return true;
    }

    private void sendScheduledHeartbeatLocked(long connectionGeneration) {
        if (!isCurrentLocked(connectionGeneration) || connection == null) {
            return;
        }
        if (awaitingHeartbeatAck) {
            return;
        }
        sendHeartbeatFrameLocked(connectionGeneration);
    }

    private void respondToHeartbeatRequestLocked(long connectionGeneration) {
        if (!isCurrentLocked(connectionGeneration) || connection == null) {
            return;
        }
        sendHeartbeatFrameLocked(connectionGeneration);
    }

    private void sendHeartbeatFrameLocked(long connectionGeneration) {
        Long sequence = snapshot == null ? null : snapshot.sequence();
        String payload;
        try {
            payload = protocolCodec.encodeHeartbeat(sequence);
        } catch (RuntimeException exception) {
            protocolFailureLocked(exception, snapshot != null);
            return;
        }
        awaitingHeartbeatAck = true;
        long expectedSerial = ++heartbeatSerial;
        cancel(ackTask);
        try {
            ackTask = Objects.requireNonNull(
                    scheduler.schedule(
                            config.heartbeatAckTimeout(),
                            () -> heartbeatAckTimedOut(connectionGeneration, expectedSerial)),
                    "scheduler returned null");
        } catch (RuntimeException exception) {
            schedulerFailureLocked(exception);
            return;
        }
        if (sendPayloadLocked(connectionGeneration, payload)) {
            notifyHeartbeatSentLocked(sequence);
        }
    }

    private void acknowledgeHeartbeatLocked() {
        boolean expected = awaitingHeartbeatAck;
        awaitingHeartbeatAck = false;
        cancel(ackTask);
        ackTask = null;
        if (expected) {
            notifyHeartbeatAcknowledgedLocked();
        }
    }

    private void heartbeatAckTimedOut(long connectionGeneration, long expectedSerial) {
        synchronized (monitor) {
            if (!isCurrentLocked(connectionGeneration)
                    || !awaitingHeartbeatAck
                    || heartbeatSerial != expectedSerial) {
                return;
            }
            beginReconnectLocked(GatewayReconnectCause.ACK_TIMEOUT, snapshot != null);
        }
    }

    private boolean scheduleNextHeartbeatLocked(long connectionGeneration) {
        cancel(heartbeatTask);
        try {
            heartbeatTask = Objects.requireNonNull(
                    scheduler.schedule(heartbeatInterval, () -> {
                        synchronized (monitor) {
                            if (!isCurrentLocked(connectionGeneration)) {
                                return;
                            }
                            sendScheduledHeartbeatLocked(connectionGeneration);
                            if (isCurrentLocked(connectionGeneration)) {
                                scheduleNextHeartbeatLocked(connectionGeneration);
                            }
                        }
                    }),
                    "scheduler returned null");
            return true;
        } catch (RuntimeException exception) {
            schedulerFailureLocked(exception);
            return false;
        }
    }

    private boolean sendPayloadLocked(long connectionGeneration, String payload) {
        GatewayConnection target = connection;
        if (target == null) {
            beginReconnectLocked(GatewayReconnectCause.SEND_FAILURE, snapshot != null);
            return false;
        }
        CompletionStage<Void> sending;
        try {
            sending = Objects.requireNonNull(target.sendText(payload), "connection.sendText returned null");
        } catch (RuntimeException exception) {
            sendFailureLocked(connectionGeneration, exception);
            return false;
        }
        sending.whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                return;
            }
            synchronized (monitor) {
                if (isCurrentLocked(connectionGeneration)) {
                    sendFailureLocked(connectionGeneration, unwrap(throwable));
                }
            }
        });
        return true;
    }

    private void sendFailureLocked(long connectionGeneration, Throwable cause) {
        if (!isCurrentLocked(connectionGeneration)) {
            return;
        }
        notifyFailureLocked(new GatewaySessionException(
                GatewayReconnectCause.SEND_FAILURE,
                "Unable to send a QQ Gateway frame",
                cause));
        beginReconnectLocked(GatewayReconnectCause.SEND_FAILURE, snapshot != null);
    }

    private void authenticationFailureLocked(long connectionGeneration, Throwable cause) {
        if (!isCurrentLocked(connectionGeneration)) {
            return;
        }
        notifyFailureLocked(new GatewaySessionException(
                GatewayReconnectCause.AUTHENTICATION_FAILURE,
                "Unable to authenticate the QQ Gateway session",
                cause));
        beginReconnectLocked(GatewayReconnectCause.AUTHENTICATION_FAILURE, snapshot != null);
    }

    private void protocolFailureLocked(Throwable cause, boolean preserveSnapshot) {
        notifyFailureLocked(new GatewaySessionException(
                GatewayReconnectCause.PROTOCOL_FAILURE,
                "QQ Gateway payload did not match the expected protocol",
                cause));
        beginReconnectLocked(GatewayReconnectCause.PROTOCOL_FAILURE, preserveSnapshot);
    }

    private void handleClosed(long connectionGeneration, int statusCode) {
        synchronized (monitor) {
            if (!isCurrentLocked(connectionGeneration)) {
                return;
            }
            if (statusCode == 4004) {
                invalidateCurrentAccessTokenLocked();
            }
            GatewayCloseDecision decision =
                    GatewayCloseClassifier.classify(statusCode, snapshot != null);
            if (decision.disposition() == GatewayCloseDisposition.STOP) {
                generation++;
                connection = null;
                cancelTimersLocked();
                transitionLocked(GatewaySessionState.STOPPED);
                notifyStoppedLocked(decision);
                return;
            }
            beginReconnectLocked(
                    decision.reconnectCause(),
                    decision.disposition() == GatewayCloseDisposition.RESUME);
        }
    }

    private void invalidateCurrentAccessTokenLocked() {
        AccessToken rejectedToken = currentAccessToken;
        currentAccessToken = null;
        if (rejectedToken == null) {
            return;
        }
        try {
            tokenProvider.invalidate(rejectedToken);
        } catch (RuntimeException exception) {
            notifyFailureLocked(exception);
        }
    }

    private void handleTransportFailure(long connectionGeneration, Throwable cause) {
        synchronized (monitor) {
            if (!isCurrentLocked(connectionGeneration)) {
                return;
            }
            notifyFailureLocked(new GatewaySessionException(
                    GatewayReconnectCause.TRANSPORT_FAILURE,
                    "QQ Gateway transport failed",
                    cause));
            beginReconnectLocked(GatewayReconnectCause.TRANSPORT_FAILURE, snapshot != null);
        }
    }

    private void beginReconnectLocked(GatewayReconnectCause cause, boolean preserveSnapshot) {
        if (state == GatewaySessionState.STOPPED || state == GatewaySessionState.BACKING_OFF) {
            return;
        }
        GatewayConnection closing = connection;
        connection = null;
        generation++;
        cancelTimersLocked();
        awaitingHeartbeatAck = false;
        if (!preserveSnapshot && snapshot != null) {
            snapshot = null;
            clearSnapshotLocked();
        }
        transitionLocked(GatewaySessionState.BACKING_OFF);
        int attempt = ++reconnectAttempt;
        Duration delay;
        try {
            delay = Objects.requireNonNull(
                    backoffStrategy.delayForAttempt(attempt, cause),
                    "backoffStrategy returned null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("backoff delay must not be negative");
            }
        } catch (RuntimeException exception) {
            notifyFailureLocked(exception);
            transitionLocked(GatewaySessionState.STOPPED);
            closeQuietly(closing);
            return;
        }
        long reconnectGeneration = generation;
        try {
            reconnectTask = Objects.requireNonNull(
                    scheduler.schedule(delay, () -> {
                        synchronized (monitor) {
                            if (state != GatewaySessionState.BACKING_OFF
                                    || generation != reconnectGeneration) {
                                return;
                            }
                        }
                        connectNow(null);
                    }),
                    "scheduler returned null");
        } catch (RuntimeException exception) {
            notifyFailureLocked(new GatewaySessionException(
                    GatewayReconnectCause.TRANSPORT_FAILURE,
                    "Unable to schedule the next QQ Gateway connection attempt",
                    exception));
            transitionLocked(GatewaySessionState.STOPPED);
            closeQuietly(closing);
            return;
        }
        notifyReconnectScheduledLocked(attempt, cause, delay);
        closeQuietly(closing);
    }

    private boolean schedulePhaseTimeoutLocked(
            long connectionGeneration,
            GatewaySessionState expectedState,
            Duration timeout) {
        cancel(phaseTimeoutTask);
        try {
            phaseTimeoutTask = Objects.requireNonNull(
                    scheduler.schedule(
                            timeout,
                            () -> protocolPhaseTimedOut(
                                    connectionGeneration, expectedState, timeout)),
                    "scheduler returned null");
            return true;
        } catch (RuntimeException exception) {
            schedulerFailureLocked(exception);
            return false;
        }
    }

    private void schedulerFailureLocked(Throwable cause) {
        GatewayConnection closing = connection;
        connection = null;
        generation++;
        cancelTimersLocked();
        awaitingHeartbeatAck = false;
        notifyFailureLocked(new GatewaySessionException(
                GatewayReconnectCause.TRANSPORT_FAILURE,
                "Unable to schedule QQ Gateway lifecycle work",
                cause));
        transitionLocked(GatewaySessionState.STOPPED);
        closeQuietly(closing);
    }

    private void protocolPhaseTimedOut(
            long connectionGeneration,
            GatewaySessionState expectedState,
            Duration timeout) {
        synchronized (monitor) {
            if (!isCurrentLocked(connectionGeneration) || state != expectedState) {
                return;
            }
            phaseTimeoutTask = null;
            String phase = expectedState == GatewaySessionState.AWAITING_HELLO
                    ? "Hello"
                    : expectedState == GatewaySessionState.RESUMING ? "RESUMED" : "READY";
            TimeoutException timeoutException =
                    new TimeoutException("Timed out waiting for QQ Gateway " + phase + " after " + timeout);
            notifyFailureLocked(new GatewaySessionException(
                    GatewayReconnectCause.PROTOCOL_FAILURE,
                    "QQ Gateway did not complete the " + phase + " phase in time",
                    timeoutException));
            boolean preserveSnapshot = expectedState != GatewaySessionState.IDENTIFYING
                    && snapshot != null;
            beginReconnectLocked(GatewayReconnectCause.PROTOCOL_FAILURE, preserveSnapshot);
        }
    }

    private void saveSnapshotLocked(GatewaySessionSnapshot value) {
        enqueuePersistenceLocked(
                () -> snapshotStore.save(value), "snapshotStore.save returned null");
    }

    private void clearSnapshotLocked() {
        enqueuePersistenceLocked(snapshotStore::clear, "snapshotStore.clear returned null");
    }

    private void enqueuePersistenceLocked(
            Supplier<CompletionStage<Void>> operation, String nullMessage) {
        persistenceTail = persistenceTail
                .handle((ignored, previousFailure) -> null)
                .thenCompose(ignored -> {
                    try {
                        return Objects.requireNonNull(operation.get(), nullMessage);
                    } catch (RuntimeException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                });
        CompletionStage<Void> observed = persistenceTail;
        observed.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                synchronized (monitor) {
                    notifyFailureLocked(unwrap(throwable));
                }
            }
        });
    }

    private void failInitialStart(CompletableFuture<Void> started, Throwable cause) {
        synchronized (monitor) {
            transitionLocked(GatewaySessionState.STOPPED);
            notifyFailureLocked(cause);
        }
        started.completeExceptionally(cause);
    }

    private boolean isCurrentLocked(long connectionGeneration) {
        return connectionGeneration == generation && state != GatewaySessionState.STOPPED;
    }

    private void cancelTimersLocked() {
        cancel(heartbeatTask);
        cancel(ackTask);
        cancel(phaseTimeoutTask);
        cancel(reconnectTask);
        heartbeatTask = null;
        ackTask = null;
        phaseTimeoutTask = null;
        reconnectTask = null;
    }

    private void transitionLocked(GatewaySessionState next) {
        if (state == next) {
            return;
        }
        GatewaySessionState previous = state;
        state = next;
        safely(() -> listener.onStateChanged(previous, next));
    }

    private void notifyReadyLocked(GatewaySessionSnapshot value) {
        safely(() -> listener.onReady(value));
    }

    private void notifyDispatchLocked(GatewayDispatch dispatch) {
        safely(() -> listener.onDispatch(dispatch));
    }

    private void notifyUnknownOpcodeLocked(int opcode, String payload) {
        safely(() -> listener.onUnknownOpcode(opcode, payload));
    }

    private void notifyHeartbeatSentLocked(Long sequence) {
        safely(() -> listener.onHeartbeatSent(sequence));
    }

    private void notifyHeartbeatAcknowledgedLocked() {
        safely(listener::onHeartbeatAcknowledged);
    }

    private void notifyReconnectScheduledLocked(
            int attempt, GatewayReconnectCause cause, Duration delay) {
        safely(() -> listener.onReconnectScheduled(attempt, cause, delay));
    }

    private void notifyFailureLocked(Throwable cause) {
        safely(() -> listener.onFailure(cause));
    }

    private void notifyStoppedLocked(GatewayCloseDecision decision) {
        safely(() -> listener.onStopped(decision));
    }

    private static void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Observer failures must not corrupt the connection state machine.
        }
    }

    private static void cancel(GatewayScheduler.Cancellable task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static void closeQuietly(GatewayConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            CompletionStage<Void> closing = connection.close();
            if (closing != null) {
                closing.exceptionally(ignored -> null);
            }
        } catch (RuntimeException ignored) {
            // The session has already fenced this connection generation.
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class TransportListener implements GatewayTransport.Listener {
        private final long connectionGeneration;

        private TransportListener(long connectionGeneration) {
            this.connectionGeneration = connectionGeneration;
        }

        @Override
        public void onText(String payload) {
            handleText(connectionGeneration, payload);
        }

        @Override
        public void onClosed(int statusCode, String reason) {
            handleClosed(connectionGeneration, statusCode);
        }

        @Override
        public void onFailure(Throwable cause) {
            handleTransportFailure(connectionGeneration, cause);
        }
    }
}
