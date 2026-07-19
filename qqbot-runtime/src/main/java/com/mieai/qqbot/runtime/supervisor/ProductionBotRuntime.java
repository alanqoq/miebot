package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.client.BotCredentials;
import com.mieai.qqbot.client.GatewayBotInfo;
import com.mieai.qqbot.client.QqClientException;
import com.mieai.qqbot.client.TokenProvider;
import com.mieai.qqbot.gateway.GatewayBackoffStrategy;
import com.mieai.qqbot.gateway.GatewayCloseDecision;
import com.mieai.qqbot.gateway.GatewayDispatch;
import com.mieai.qqbot.gateway.GatewayReconnectCause;
import com.mieai.qqbot.gateway.GatewayScheduler;
import com.mieai.qqbot.gateway.GatewaySession;
import com.mieai.qqbot.gateway.GatewaySessionConfig;
import com.mieai.qqbot.gateway.GatewaySessionException;
import com.mieai.qqbot.gateway.GatewaySessionListener;
import com.mieai.qqbot.gateway.GatewaySessionSnapshot;
import com.mieai.qqbot.gateway.GatewaySessionState;
import com.mieai.qqbot.gateway.GatewaySnapshotStore;
import com.mieai.qqbot.gateway.GatewayTransport;
import com.mieai.qqbot.domain.bot.BotDefinition;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** One production bot runtime from OpenAPI discovery through the Gateway session lifecycle. */
final class ProductionBotRuntime implements ManagedBotRuntime {
    private static final BotRuntimeFailure SESSION_LIMITED = new BotRuntimeFailure(
            "GATEWAY_SESSION_LIMITED",
            "QQ has temporarily exhausted this bot's Gateway session creation allowance",
            true);
    private static final BotRuntimeFailure SHARD_CONFIGURATION_INVALID = new BotRuntimeFailure(
            "GATEWAY_SHARD_CONFIGURATION_INVALID",
            "The configured shard count exceeds the count recommended by QQ",
            false);
    private static final BotRuntimeFailure RETRY_SCHEDULING_FAILED = new BotRuntimeFailure(
            "GATEWAY_RETRY_SCHEDULING_FAILED",
            "The bot runtime could not schedule its next connection attempt",
            false);
    private final Object monitor = new Object();
    private final BotDefinition definition;
    private final BotCredentials credentials;
    private final GatewayDiscovery discovery;
    private final TokenProvider tokenProvider;
    private final GatewayTransport gatewayTransport;
    private final GatewaySnapshotStore snapshotStore;
    private final RuntimeScheduler scheduler;
    private final GatewayBackoffStrategy backoffStrategy;
    private final BotRuntimeObserver observer;

    private Lifecycle lifecycle = Lifecycle.NEW;
    private long generation;
    private int retryAttempt;
    private GatewaySession gatewaySession;
    private GatewayScheduler.Cancellable discoveryRetry;
    private CompletableFuture<Void> initialStart;
    private CompletableFuture<Void> stopResult;

    ProductionBotRuntime(
            BotDefinition definition,
            BotCredentials credentials,
            GatewayDiscovery discovery,
            TokenProvider tokenProvider,
            GatewayTransport gatewayTransport,
            GatewaySnapshotStore snapshotStore,
            RuntimeScheduler scheduler,
            GatewayBackoffStrategy backoffStrategy,
            BotRuntimeObserver observer) {
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.discovery = Objects.requireNonNull(discovery, "discovery must not be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        this.gatewayTransport =
                Objects.requireNonNull(gatewayTransport, "gatewayTransport must not be null");
        this.snapshotStore =
                Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.backoffStrategy =
                Objects.requireNonNull(backoffStrategy, "backoffStrategy must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
    }

    @Override
    public CompletionStage<Void> start() {
        CompletableFuture<Void> result;
        long currentGeneration;
        synchronized (monitor) {
            if (lifecycle != Lifecycle.NEW) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("The bot runtime has already been started"));
            }
            lifecycle = Lifecycle.RUNNING;
            currentGeneration = ++generation;
            result = new CompletableFuture<>();
            initialStart = result;
        }
        publishState(BotRuntimeState.STARTING);
        discover(currentGeneration);
        return result.minimalCompletionStage();
    }

    @Override
    public CompletionStage<Void> stop() {
        GatewaySession stoppingSession;
        GatewayScheduler.Cancellable retry;
        CompletableFuture<Void> pendingStart;
        CompletableFuture<Void> result;
        synchronized (monitor) {
            if (stopResult != null) {
                return stopResult.minimalCompletionStage();
            }
            result = new CompletableFuture<>();
            stopResult = result;
            if (lifecycle == Lifecycle.STOPPED) {
                result.complete(null);
                return result.minimalCompletionStage();
            }
            lifecycle = Lifecycle.STOPPING;
            generation++;
            stoppingSession = gatewaySession;
            gatewaySession = null;
            retry = discoveryRetry;
            discoveryRetry = null;
            pendingStart = initialStart;
        }

        publishState(BotRuntimeState.STOPPING);
        cancel(retry);
        CompletionStage<Void> stopping = stopSession(stoppingSession);
        boolean localCleanupFailed = false;
        try {
            scheduler.close();
        } catch (RuntimeException exception) {
            localCleanupFailed = true;
        }
        try {
            credentials.close();
        } catch (RuntimeException exception) {
            localCleanupFailed = true;
        }
        if (pendingStart != null) {
            pendingStart.completeExceptionally(new IllegalStateException(
                    "The bot runtime was stopped during its initial connection attempt"));
        }
        boolean cleanupFailed = localCleanupFailed;
        stopping.whenComplete((ignored, failure) -> {
            synchronized (monitor) {
                lifecycle = Lifecycle.STOPPED;
            }
            publishState(BotRuntimeState.STOPPED);
            if (failure != null || cleanupFailed) {
                result.completeExceptionally(
                        new IllegalStateException("The bot runtime could not fully release its resources"));
            } else {
                result.complete(null);
            }
        });
        return result.minimalCompletionStage();
    }

    private void discover(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) {
            return;
        }
        publishState(BotRuntimeState.DISCOVERING);
        CompletionStage<GatewayBotInfo> discovering;
        try {
            discovering = Objects.requireNonNull(
                    discovery.discover(), "discovery returned null");
        } catch (RuntimeException exception) {
            handleDiscoveryFailure(expectedGeneration, exception);
            return;
        }
        discovering.whenComplete((gateway, failure) -> {
            if (failure != null) {
                handleDiscoveryFailure(expectedGeneration, unwrap(failure));
                return;
            }
            if (gateway == null) {
                handleDiscoveryFailure(
                        expectedGeneration,
                        new NullPointerException("discovery returned a null Gateway response"));
                return;
            }
            try {
                handleDiscoverySuccess(expectedGeneration, gateway);
            } catch (RuntimeException exception) {
                handleDiscoveryFailure(expectedGeneration, exception);
            }
        });
    }

    private void handleDiscoverySuccess(long expectedGeneration, GatewayBotInfo gateway) {
        if (!isCurrent(expectedGeneration)) {
            return;
        }
        if (definition.shardSpec().count() > gateway.recommendedShardCount()) {
            failTerminal(SHARD_CONFIGURATION_INVALID);
            completeInitialFailure();
            return;
        }
        if (gateway.sessionStartLimit().remaining() == 0) {
            publishFailure(SESSION_LIMITED);
            scheduleDiscoveryRetry(
                    expectedGeneration,
                    normalizedLimitDelay(gateway.sessionStartLimit().resetAfter()));
            completeInitialFailure();
            return;
        }

        GatewaySession created = new GatewaySession(
                GatewaySessionConfig.defaults(
                        gateway.url(), definition.intents(), definition.shardSpec()),
                tokenProvider,
                gatewayTransport,
                snapshotStore,
                backoffStrategy,
                scheduler,
                new SessionObserver(expectedGeneration));
        synchronized (monitor) {
            if (!acceptsLocked(expectedGeneration)) {
                created.stop();
                return;
            }
            discoveryRetry = null;
            gatewaySession = created;
        }

        CompletionStage<Void> starting;
        try {
            starting = Objects.requireNonNull(
                    created.start(), "GatewaySession.start returned null");
        } catch (RuntimeException exception) {
            completeInitialFailure();
            handleSessionStartFailure(expectedGeneration, created);
            return;
        }
        starting.whenComplete((ignored, failure) -> {
            if (failure == null) {
                completeInitialSuccess();
                return;
            }
            completeInitialFailure();
            handleSessionStartFailure(expectedGeneration, created);
        });
    }

    private void handleSessionStartFailure(
            long expectedGeneration, GatewaySession failedSession) {
        if (!isCurrentSession(expectedGeneration, failedSession)) {
            return;
        }
        if (failedSession.state() != GatewaySessionState.STOPPED) {
            return;
        }
        synchronized (monitor) {
            if (!acceptsLocked(expectedGeneration) || gatewaySession != failedSession) {
                return;
            }
            gatewaySession = null;
        }
        failedSession.stop();
        scheduleDiscoveryRetry(expectedGeneration, null);
    }

    private void handleDiscoveryFailure(long expectedGeneration, Throwable cause) {
        if (!isCurrent(expectedGeneration)) {
            return;
        }
        BotRuntimeFailure failure = mapDiscoveryFailure(cause);
        publishFailure(failure);
        completeInitialFailure();
        if (failure.retryable()) {
            scheduleDiscoveryRetry(expectedGeneration, null);
        } else {
            publishState(BotRuntimeState.FAILED);
        }
    }

    private void scheduleDiscoveryRetry(
            long expectedGeneration, Duration requestedDelay) {
        Duration delay;
        int attempt;
        synchronized (monitor) {
            if (!acceptsLocked(expectedGeneration)) {
                return;
            }
            attempt = ++retryAttempt;
        }
        try {
            Duration backoff = Objects.requireNonNull(
                    backoffStrategy.delayForAttempt(
                            attempt, GatewayReconnectCause.TRANSPORT_FAILURE),
                    "backoffStrategy returned null");
            if (backoff.isNegative()) {
                throw new IllegalArgumentException("backoff delay must not be negative");
            }
            delay = requestedDelay == null || requestedDelay.compareTo(backoff) < 0
                    ? backoff
                    : requestedDelay;
        } catch (RuntimeException exception) {
            failTerminal(RETRY_SCHEDULING_FAILED);
            return;
        }

        GatewayScheduler.Cancellable scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    scheduler.schedule(delay, () -> {
                        synchronized (monitor) {
                            if (!acceptsLocked(expectedGeneration)) {
                                return;
                            }
                            discoveryRetry = null;
                        }
                        discover(expectedGeneration);
                    }),
                    "scheduler returned null");
        } catch (RuntimeException exception) {
            failTerminal(RETRY_SCHEDULING_FAILED);
            return;
        }
        synchronized (monitor) {
            if (!acceptsLocked(expectedGeneration)) {
                scheduled.cancel();
                return;
            }
            cancel(discoveryRetry);
            discoveryRetry = scheduled;
        }
        publishState(BotRuntimeState.RECONNECTING);
        safely(observer::onReconnectScheduled);
    }

    private boolean isCurrent(long expectedGeneration) {
        synchronized (monitor) {
            return acceptsLocked(expectedGeneration);
        }
    }

    private boolean isCurrentSession(
            long expectedGeneration, GatewaySession expectedSession) {
        synchronized (monitor) {
            return acceptsLocked(expectedGeneration) && gatewaySession == expectedSession;
        }
    }

    private boolean acceptsLocked(long expectedGeneration) {
        return lifecycle == Lifecycle.RUNNING && generation == expectedGeneration;
    }

    private void completeInitialSuccess() {
        CompletableFuture<Void> result;
        synchronized (monitor) {
            result = initialStart;
        }
        if (result != null) {
            result.complete(null);
        }
    }

    private void completeInitialFailure() {
        CompletableFuture<Void> result;
        synchronized (monitor) {
            result = initialStart;
        }
        if (result != null) {
            result.completeExceptionally(new IllegalStateException(
                    "The bot runtime did not complete its initial connection attempt"));
        }
    }

    private void failTerminal(BotRuntimeFailure failure) {
        publishFailure(failure);
        publishState(BotRuntimeState.FAILED);
    }

    private void publishState(BotRuntimeState state) {
        safely(() -> observer.onStateChanged(state));
    }

    private void publishFailure(BotRuntimeFailure failure) {
        safely(() -> observer.onFailure(failure));
    }

    private static CompletionStage<Void> stopSession(GatewaySession session) {
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return Objects.requireNonNull(session.stop(), "GatewaySession.stop returned null");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static Duration normalizedLimitDelay(Duration resetAfter) {
        Objects.requireNonNull(resetAfter, "resetAfter must not be null");
        return resetAfter.isZero() ? Duration.ofSeconds(1) : resetAfter;
    }

    private static BotRuntimeFailure mapDiscoveryFailure(Throwable cause) {
        if (cause instanceof QqClientException clientException) {
            return switch (clientException.failure()) {
                case AUTHENTICATION -> new BotRuntimeFailure(
                        "QQ_AUTHENTICATION_REJECTED", "QQ rejected the bot credentials", false);
                case TIMEOUT -> new BotRuntimeFailure(
                        "QQ_REQUEST_TIMEOUT", "QQ did not answer the Gateway discovery request in time", true);
                case TRANSPORT -> new BotRuntimeFailure(
                        "QQ_TRANSPORT_UNAVAILABLE", "QQ Gateway discovery is temporarily unreachable", true);
                case PROTOCOL -> new BotRuntimeFailure(
                        "QQ_PROTOCOL_ERROR", "QQ returned an invalid Gateway discovery response", false);
                case HTTP_STATUS -> mapHttpFailure(clientException);
            };
        }
        return new BotRuntimeFailure(
                "GATEWAY_DISCOVERY_FAILED", "Gateway discovery could not be completed", true);
    }

    private static BotRuntimeFailure mapHttpFailure(QqClientException exception) {
        int status = exception.httpStatus().orElse(-1);
        if (status == 401 || status == 403) {
            return new BotRuntimeFailure(
                    "QQ_AUTHENTICATION_REJECTED", "QQ rejected the bot credentials", false);
        }
        if (status == 429) {
            return new BotRuntimeFailure(
                    "QQ_RATE_LIMITED", "QQ temporarily rate limited Gateway discovery", true);
        }
        if (status >= 500) {
            return new BotRuntimeFailure(
                    "QQ_SERVICE_UNAVAILABLE", "QQ Gateway discovery is temporarily unavailable", true);
        }
        return new BotRuntimeFailure(
                "QQ_REQUEST_REJECTED", "QQ rejected the Gateway discovery request", false);
    }

    private static BotRuntimeFailure mapGatewayFailure(Throwable cause) {
        if (cause instanceof GatewaySessionException sessionException) {
            GatewayReconnectCause category = sessionException.category();
            return new BotRuntimeFailure(
                    "GATEWAY_" + category.name(), gatewayFailureMessage(category), true);
        }
        return new BotRuntimeFailure(
                "GATEWAY_RUNTIME_FAILURE", "The QQ Gateway session encountered an internal failure", true);
    }

    private static String gatewayFailureMessage(GatewayReconnectCause cause) {
        return switch (cause) {
            case SERVER_RECONNECT -> "QQ requested a Gateway reconnect";
            case INVALID_SESSION -> "QQ rejected the resumable Gateway session";
            case ACK_TIMEOUT -> "QQ did not acknowledge a Gateway heartbeat in time";
            case CONNECTION_CLOSED -> "The QQ Gateway connection was closed";
            case RATE_LIMITED -> "QQ rate limited the Gateway connection";
            case AUTHENTICATION_FAILURE -> "The QQ Gateway authentication attempt failed";
            case TRANSPORT_FAILURE -> "The QQ Gateway transport failed";
            case PROTOCOL_FAILURE -> "The QQ Gateway returned an invalid payload";
            case SEND_FAILURE -> "A QQ Gateway frame could not be sent";
            case DISPATCH_REJECTED -> "The application could not persist a QQ Gateway event";
        };
    }

    private static BotRuntimeFailure mapStopped(GatewayCloseDecision decision) {
        return switch (decision.statusCode()) {
            case 4010, 4011 -> new BotRuntimeFailure(
                    "GATEWAY_SHARD_REJECTED", "QQ rejected the configured Gateway shard", false);
            case 4013, 4014 -> new BotRuntimeFailure(
                    "GATEWAY_INTENTS_REJECTED", "QQ rejected the configured Gateway event permissions", false);
            case 4914 -> new BotRuntimeFailure(
                    "QQ_BOT_OFFLINE", "The QQ bot is not available in this environment", false);
            case 4915 -> new BotRuntimeFailure(
                    "QQ_BOT_BANNED", "The QQ bot is currently prohibited from connecting", false);
            default -> new BotRuntimeFailure(
                    "GATEWAY_STOPPED", "QQ permanently rejected the Gateway connection", false);
        };
    }

    private static BotRuntimeState mapState(GatewaySessionState state) {
        return switch (state) {
            case NEW -> BotRuntimeState.STARTING;
            case LOADING_SNAPSHOT, CONNECTING, AWAITING_HELLO -> BotRuntimeState.CONNECTING;
            case IDENTIFYING, RESUMING -> BotRuntimeState.AUTHENTICATING;
            case READY -> BotRuntimeState.ONLINE;
            case BACKING_OFF -> BotRuntimeState.RECONNECTING;
            case STOPPED -> BotRuntimeState.STOPPED;
        };
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Runtime observers are diagnostic and must not corrupt a live Gateway session.
        }
    }

    private static void cancel(GatewayScheduler.Cancellable task) {
        if (task != null) {
            task.cancel();
        }
    }

    private final class SessionObserver implements GatewaySessionListener {
        private final long expectedGeneration;
        private Long heartbeatSequence;

        private SessionObserver(long expectedGeneration) {
            this.expectedGeneration = expectedGeneration;
        }

        @Override
        public void onStateChanged(GatewaySessionState previous, GatewaySessionState current) {
            if (isCurrent(expectedGeneration)) {
                publishState(mapState(current));
            }
        }

        @Override
        public void onReady(GatewaySessionSnapshot snapshot) {
            if (!isCurrent(expectedGeneration)) {
                return;
            }
            synchronized (monitor) {
                retryAttempt = 0;
            }
            safely(() -> observer.onReady(
                    new BotSessionSnapshot(snapshot.sessionId(), snapshot.sequence())));
        }

        @Override
        public boolean acceptDispatch(GatewayDispatch dispatch) {
            if (!isCurrent(expectedGeneration)) {
                // A stop/rebuild may race a final transport callback. Reject it so the Gateway
                // keeps the previous resumable sequence instead of acknowledging an event that
                // could not reach the active Inbox.
                return false;
            }
            return observer.acceptDispatch(dispatch);
        }

        @Override
        public void onDispatch(GatewayDispatch dispatch) {
            if (isCurrent(expectedGeneration)) {
                safely(() -> observer.onDispatch(dispatch));
            }
        }

        @Override
        public void onHeartbeatSent(Long sequence) {
            heartbeatSequence = sequence;
        }

        @Override
        public void onHeartbeatAcknowledged() {
            if (!isCurrent(expectedGeneration)) {
                return;
            }
            Long sequence = heartbeatSequence;
            if (sequence == null) {
                safely(observer::onHeartbeatAcknowledged);
            } else {
                safely(() -> observer.onHeartbeatAcknowledged(sequence));
            }
        }

        @Override
        public void onReconnectScheduled(
                int attempt, GatewayReconnectCause cause, Duration delay) {
            if (isCurrent(expectedGeneration)) {
                safely(observer::onReconnectScheduled);
            }
        }

        @Override
        public void onFailure(Throwable cause) {
            if (isCurrent(expectedGeneration)) {
                publishFailure(mapGatewayFailure(cause));
            }
        }

        @Override
        public void onStopped(GatewayCloseDecision decision) {
            if (!isCurrent(expectedGeneration)) {
                return;
            }
            publishFailure(mapStopped(decision));
            publishState(BotRuntimeState.FAILED);
        }
    }

    private enum Lifecycle {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
