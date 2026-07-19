package com.mieai.qqbot.runtime.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.client.AccessToken;
import com.mieai.qqbot.client.BotCredentials;
import com.mieai.qqbot.client.GatewayBotInfo;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.client.SessionStartLimit;
import com.mieai.qqbot.client.TokenProvider;
import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.gateway.GatewayBackoffStrategy;
import com.mieai.qqbot.gateway.GatewayConnection;
import com.mieai.qqbot.gateway.GatewayDispatch;
import com.mieai.qqbot.gateway.GatewaySnapshotStore;
import com.mieai.qqbot.gateway.GatewayTransport;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.security.AesGcmAppSecretCipher;
import com.mieai.qqbot.runtime.security.AppSecret;
import com.mieai.qqbot.runtime.security.AppSecretBinding;
import com.mieai.qqbot.runtime.security.StaticKeyProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductionBotRuntimeTest {
    private static final URI GATEWAY_URL = URI.create("wss://gateway.example/websocket");
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void factoryDecryptsCredentialUsesRealQqClientsAndPublishesGatewayState() throws Exception {
        try (QqHttpFixture qq = new QqHttpFixture(200, gatewayResponse(2, 999, 60_000L))) {
            StoredBot stored = encryptedBot(ShardSpec.single());
            FakeGatewayTransport transport = new FakeGatewayTransport();
            ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
            RecordingObserver observer = new RecordingObserver();
            ProductionBotRuntimeFactory factory = factory(qq, transport, scheduler);

            ManagedBotRuntime runtime = factory.create(stored, observer);
            runtime.start().toCompletableFuture().join();

            assertThat(qq.tokenRequestBody())
                    .contains("\"appId\":\"1905208810\"")
                    .contains("\"clientSecret\":\"factory-secret\"");
            assertThat(qq.gatewayAuthorization()).isEqualTo("QQBot access-token");
            assertThat(transport.connectedUrl()).isEqualTo(GATEWAY_URL);
            assertThat(observer.states())
                    .containsSubsequence(
                            BotRuntimeState.STARTING,
                            BotRuntimeState.DISCOVERING,
                            BotRuntimeState.CONNECTING);

            transport.emit(hello());
            assertThat(transport.latestPayload())
                    .contains("\"op\":2")
                    .contains("\"token\":\"QQBot access-token\"")
                    .contains("\"intents\":33554432");

            transport.emit(ready(1L));
            transport.emit("{\"op\":1}");
            transport.emit("{\"op\":11}");
            String dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\","
                    + "\"id\":\"event-runtime-1\",\"d\":{\"content\":\"hello\"}}";
            transport.emit(dispatch);

            assertThat(observer.states()).contains(BotRuntimeState.AUTHENTICATING, BotRuntimeState.ONLINE);
            assertThat(observer.snapshots())
                    .containsExactly(new BotSessionSnapshot("session-a", 1L));
            assertThat(observer.heartbeatSequences()).containsExactly(1L);
            assertThat(observer.dispatchSequences()).containsExactly(2L);
            assertThat(observer.dispatches()).containsExactly(
                    new GatewayDispatch(
                            2L, "C2C_MESSAGE_CREATE", dispatch, "event-runtime-1", "session-a"));

            runtime.stop().toCompletableFuture().join();

            assertThat(transport.closeCount()).isEqualTo(1);
            assertThat(scheduler.closed()).isTrue();
            assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.STOPPED);
        }
    }

    @Test
    void waitsForSessionLimitResetWithoutOpeningGatewayOrTightLooping() {
        BotDefinition definition = definition(ShardSpec.single());
        BotCredentials credentials = credentials(definition);
        AtomicInteger discoveries = new AtomicInteger();
        GatewayDiscovery discovery = () -> {
            discoveries.incrementAndGet();
            return CompletableFuture.completedFuture(new GatewayBotInfo(
                    GATEWAY_URL,
                    1,
                    new SessionStartLimit(1000, 0, Duration.ofSeconds(12), 1)));
        };
        FakeGatewayTransport transport = new FakeGatewayTransport();
        ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
        RecordingObserver observer = new RecordingObserver();
        ProductionBotRuntime runtime = runtime(
                definition, credentials, discovery, transport, scheduler, observer);

        assertThatThrownBy(() -> runtime.start().toCompletableFuture().join())
                .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt");

        assertThat(discoveries).hasValue(1);
        assertThat(transport.connectionCount()).isZero();
        assertThat(observer.failures()).containsExactly(new BotRuntimeFailure(
                "GATEWAY_SESSION_LIMITED",
                "QQ has temporarily exhausted this bot's Gateway session creation allowance",
                true));
        assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.RECONNECTING);
        assertThat(scheduler.nextDelay()).contains(Duration.ofSeconds(12));

        scheduler.advance(Duration.ofSeconds(11));
        assertThat(discoveries).hasValue(1);
        scheduler.advance(Duration.ofSeconds(1));
        assertThat(discoveries).hasValue(2);

        runtime.stop().toCompletableFuture().join();
        assertThat(credentials.appSecret().isDestroyed()).isTrue();
    }

    @Test
    void rejectsShardCountAboveQqRecommendationBeforeIdentify() {
        BotDefinition definition = definition(new ShardSpec(0, 2));
        BotCredentials credentials = credentials(definition);
        GatewayDiscovery discovery = () -> CompletableFuture.completedFuture(new GatewayBotInfo(
                GATEWAY_URL,
                1,
                new SessionStartLimit(1000, 999, Duration.ofHours(1), 1)));
        FakeGatewayTransport transport = new FakeGatewayTransport();
        ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
        RecordingObserver observer = new RecordingObserver();
        ProductionBotRuntime runtime = runtime(
                definition, credentials, discovery, transport, scheduler, observer);

        assertThatThrownBy(() -> runtime.start().toCompletableFuture().join())
                .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt");

        assertThat(transport.connectionCount()).isZero();
        assertThat(observer.failures()).containsExactly(new BotRuntimeFailure(
                "GATEWAY_SHARD_CONFIGURATION_INVALID",
                "The configured shard count exceeds the count recommended by QQ",
                false));
        assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.FAILED);
        assertThat(scheduler.nextDelay()).isEmpty();

        runtime.stop().toCompletableFuture().join();
    }

    @Test
    void mapsPermanentGatewayCloseWithoutExposingWireReason() {
        BotDefinition definition = definition(ShardSpec.single());
        BotCredentials credentials = credentials(definition);
        GatewayDiscovery discovery = () -> CompletableFuture.completedFuture(new GatewayBotInfo(
                GATEWAY_URL,
                1,
                new SessionStartLimit(1000, 999, Duration.ofHours(1), 1)));
        FakeGatewayTransport transport = new FakeGatewayTransport();
        ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
        RecordingObserver observer = new RecordingObserver();
        ProductionBotRuntime runtime = runtime(
                definition, credentials, discovery, transport, scheduler, observer);
        runtime.start().toCompletableFuture().join();
        transport.emit(hello());
        transport.emit(ready(1L));

        transport.closeFromServer(4014, "remote-secret-detail");

        assertThat(observer.failures().getLast())
                .isEqualTo(new BotRuntimeFailure(
                        "GATEWAY_INTENTS_REJECTED",
                        "QQ rejected the configured Gateway event permissions",
                        false));
        assertThat(observer.failures().getLast().toString())
                .doesNotContain("remote-secret-detail");
        assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.FAILED);

        runtime.stop().toCompletableFuture().join();
        assertThat(credentials.appSecret().isDestroyed()).isTrue();
        assertThat(scheduler.closed()).isTrue();
    }

    @Test
    void keepsGatewayInternalReconnectAfterInitialTransportFailure() {
        BotDefinition definition = definition(ShardSpec.single());
        BotCredentials credentials = credentials(definition);
        GatewayDiscovery discovery = () -> CompletableFuture.completedFuture(new GatewayBotInfo(
                GATEWAY_URL,
                1,
                new SessionStartLimit(1000, 999, Duration.ofHours(1), 1)));
        FakeGatewayTransport transport = new FakeGatewayTransport();
        transport.failNextConnect();
        ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
        RecordingObserver observer = new RecordingObserver();
        ProductionBotRuntime runtime = runtime(
                definition, credentials, discovery, transport, scheduler, observer);

        assertThatThrownBy(() -> runtime.start().toCompletableFuture().join())
                .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt");

        assertThat(transport.connectionCount()).isZero();
        assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.RECONNECTING);
        assertThat(observer.failures().getLast().code()).isEqualTo("GATEWAY_TRANSPORT_FAILURE");
        assertThat(scheduler.nextDelay()).contains(Duration.ofSeconds(1));
        assertThat(credentials.appSecret().isDestroyed()).isFalse();

        scheduler.advance(Duration.ofSeconds(1));
        assertThat(transport.connectionCount()).isEqualTo(1);
        transport.emit(hello());
        transport.emit(ready(1L));

        assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.ONLINE);
        assertThat(observer.snapshots())
                .containsExactly(new BotSessionSnapshot("session-a", 1L));

        runtime.stop().toCompletableFuture().join();
    }

    @Test
    void stopFencesCallbacksAndClosesCredentialsAndSchedulerBeforeReturning() {
        BotDefinition definition = definition(ShardSpec.single());
        BotCredentials credentials = credentials(definition);
        GatewayDiscovery discovery = () -> CompletableFuture.completedFuture(new GatewayBotInfo(
                GATEWAY_URL,
                1,
                new SessionStartLimit(1000, 999, Duration.ofHours(1), 1)));
        FakeGatewayTransport transport = new FakeGatewayTransport();
        ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
        RecordingObserver observer = new RecordingObserver();
        ProductionBotRuntime runtime = runtime(
                definition, credentials, discovery, transport, scheduler, observer);
        runtime.start().toCompletableFuture().join();
        transport.emit(hello());
        transport.emit(ready(1L));

        CompletionStage<Void> stopping = runtime.stop();

        assertThat(credentials.appSecret().isDestroyed()).isTrue();
        assertThat(scheduler.closed()).isTrue();
        stopping.toCompletableFuture().join();
        List<BotRuntimeState> stoppedStates = observer.states();
        List<BotRuntimeFailure> stoppedFailures = observer.failures();
        List<Long> stoppedDispatches = observer.dispatchSequences();

        transport.emit("{\"op\":0,\"s\":9,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{}}");
        transport.closeFromServer(4014, "late-close");
        transport.failFromServer(new IOException("late-failure"));

        assertThat(observer.states()).isEqualTo(stoppedStates);
        assertThat(observer.failures()).isEqualTo(stoppedFailures);
        assertThat(observer.dispatchSequences()).isEqualTo(stoppedDispatches);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void mapsRemoteAuthenticationErrorToStableFailure(int status) throws Exception {
        String remoteBody = "{\"code\":11241,\"message\":\"remote-sensitive-detail\"}";
        try (QqHttpFixture qq = new QqHttpFixture(status, remoteBody)) {
            StoredBot stored = encryptedBot(ShardSpec.single());
            FakeGatewayTransport transport = new FakeGatewayTransport();
            ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
            RecordingObserver observer = new RecordingObserver();
            ManagedBotRuntime runtime = factory(qq, transport, scheduler).create(stored, observer);

            assertThatThrownBy(() -> runtime.start().toCompletableFuture().join())
                    .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt");

            BotRuntimeFailure failure = observer.failures().getLast();
            assertThat(failure).isEqualTo(new BotRuntimeFailure(
                    "QQ_AUTHENTICATION_REJECTED", "QQ rejected the bot credentials", false));
            assertThat(failure.toString())
                    .doesNotContain("remote-sensitive-detail")
                    .doesNotContain(qq.baseUri().toString());
            assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.FAILED);

            runtime.stop().toCompletableFuture().join();
        }
    }

    @Test
    void mapsTokenHttp200BusinessErrorToNonRetryableAuthenticationFailure() throws Exception {
        try (QqHttpFixture qq = QqHttpFixture.tokenBusinessError(
                "{\"code\":11241,\"message\":\"factory-secret remote-sensitive-detail\"}")) {
            StoredBot stored = encryptedBot(ShardSpec.single());
            FakeGatewayTransport transport = new FakeGatewayTransport();
            ManualRuntimeScheduler scheduler = new ManualRuntimeScheduler();
            RecordingObserver observer = new RecordingObserver();
            ManagedBotRuntime runtime = factory(qq, transport, scheduler).create(stored, observer);

            assertThatThrownBy(() -> runtime.start().toCompletableFuture().join())
                    .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt");

            BotRuntimeFailure failure = observer.failures().getLast();
            assertThat(failure).isEqualTo(new BotRuntimeFailure(
                    "QQ_AUTHENTICATION_REJECTED", "QQ rejected the bot credentials", false));
            assertThat(failure.toString())
                    .doesNotContain("factory-secret")
                    .doesNotContain("remote-sensitive-detail");
            assertThat(observer.states().getLast()).isEqualTo(BotRuntimeState.FAILED);
            assertThat(transport.connectionCount()).isZero();
            assertThat(scheduler.nextDelay()).isEmpty();
            assertThat(qq.gatewayAuthorization()).isNull();

            runtime.stop().toCompletableFuture().join();
        }
    }

    private static ProductionBotRuntimeFactory factory(
            QqHttpFixture qq,
            FakeGatewayTransport transport,
            ManualRuntimeScheduler scheduler) {
        QqClientOptions options = QqClientOptions.builder()
                .tokenEndpoint(qq.uri("/token"))
                .openApiBaseUri(qq.baseUri())
                .requestTimeout(Duration.ofSeconds(2))
                .build();
        return new ProductionBotRuntimeFactory(
                cipher(),
                environment -> options,
                transport,
                bot -> GatewaySnapshotStore.none(),
                definition -> scheduler,
                GatewayBackoffStrategy.fixed(Duration.ofSeconds(1)));
    }

    private static ProductionBotRuntime runtime(
            BotDefinition definition,
            BotCredentials credentials,
            GatewayDiscovery discovery,
            FakeGatewayTransport transport,
            ManualRuntimeScheduler scheduler,
            RecordingObserver observer) {
        TokenProvider tokenProvider = () -> CompletableFuture.completedFuture(
                AccessToken.of("access-token", NOW.plus(Duration.ofHours(1))));
        return new ProductionBotRuntime(
                definition,
                credentials,
                discovery,
                tokenProvider,
                transport,
                GatewaySnapshotStore.none(),
                scheduler,
                GatewayBackoffStrategy.fixed(Duration.ofSeconds(1)),
                observer);
    }

    private static StoredBot encryptedBot(ShardSpec shardSpec) {
        BotDefinition definition = definition(shardSpec);
        AesGcmAppSecretCipher cipher = cipher();
        SecretCiphertext encrypted;
        try (AppSecret secret = AppSecret.of("factory-secret")) {
            encrypted = cipher.encrypt(secret, new AppSecretBinding(
                    definition.id(), definition.appId(), definition.environment()));
        }
        return new StoredBot(definition, encrypted);
    }

    private static AesGcmAppSecretCipher cipher() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        try {
            return new AesGcmAppSecretCipher(
                    StaticKeyProvider.configured("runtime-test-key", key));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static BotDefinition definition(ShardSpec shardSpec) {
        return new BotDefinition(
                BotId.of(UUID.fromString("3d5187c3-f958-4b8f-babb-040bcf641d42")),
                "Production Bot",
                QqAppId.of("1905208810"),
                BotEnvironment.PRODUCTION,
                GatewayIntents.of(1L << 25),
                shardSpec,
                true,
                BotRevision.initial(),
                NOW,
                NOW);
    }

    private static BotCredentials credentials(BotDefinition definition) {
        return new BotCredentials(
                definition.appId(), com.mieai.qqbot.client.AppSecret.of("client-secret"));
    }

    private static String gatewayResponse(
            int shards, int remaining, long resetAfterMillis) {
        return "{\"url\":\"" + GATEWAY_URL + "\",\"shards\":" + shards
                + ",\"session_start_limit\":{\"total\":1000,\"remaining\":" + remaining
                + ",\"reset_after\":" + resetAfterMillis + ",\"max_concurrency\":1}}";
    }

    private static String hello() {
        return "{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}";
    }

    private static String ready(long sequence) {
        return "{\"op\":0,\"s\":" + sequence + ",\"t\":\"READY\",\"d\":{"
                + "\"version\":1,\"session_id\":\"session-a\","
                + "\"user\":{\"id\":\"6158788878435714165\","
                + "\"username\":\"Gateway Bot\",\"bot\":true},\"shard\":[0,1]}}";
    }

    private static final class RecordingObserver implements BotRuntimeObserver {
        private final List<BotRuntimeState> states = new ArrayList<>();
        private final List<BotSessionSnapshot> snapshots = new ArrayList<>();
        private final List<Long> heartbeatSequences = new ArrayList<>();
        private final List<Long> dispatchSequences = new ArrayList<>();
        private final List<GatewayDispatch> dispatches = new ArrayList<>();
        private final List<BotRuntimeFailure> failures = new ArrayList<>();

        @Override
        public void onStateChanged(BotRuntimeState state) {
            states.add(state);
        }

        @Override
        public void onReady(BotSessionSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public void onHeartbeatAcknowledged(long sequence) {
            heartbeatSequences.add(sequence);
        }

        @Override
        public void onDispatch(GatewayDispatch dispatch) {
            dispatches.add(dispatch);
            dispatchSequences.add(dispatch.sequence());
        }

        @Override
        public void onFailure(BotRuntimeFailure failure) {
            failures.add(failure);
        }

        List<BotRuntimeState> states() {
            return List.copyOf(states);
        }

        List<BotSessionSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }

        List<Long> heartbeatSequences() {
            return List.copyOf(heartbeatSequences);
        }

        List<Long> dispatchSequences() {
            return List.copyOf(dispatchSequences);
        }

        List<GatewayDispatch> dispatches() {
            return List.copyOf(dispatches);
        }

        List<BotRuntimeFailure> failures() {
            return List.copyOf(failures);
        }
    }

    private static final class FakeGatewayTransport implements GatewayTransport {
        private Listener listener;
        private FakeConnection connection;
        private URI connectedUrl;
        private boolean failNextConnect;

        @Override
        public CompletionStage<GatewayConnection> connect(URI gatewayUrl, Listener listener) {
            connectedUrl = gatewayUrl;
            this.listener = listener;
            if (failNextConnect) {
                failNextConnect = false;
                return CompletableFuture.failedFuture(
                        new IOException("initial transport failure"));
            }
            connection = new FakeConnection();
            return CompletableFuture.completedFuture(connection);
        }

        void failNextConnect() {
            failNextConnect = true;
        }

        void emit(String payload) {
            listener.onText(payload);
        }

        void closeFromServer(int statusCode, String reason) {
            listener.onClosed(statusCode, reason);
        }

        void failFromServer(Throwable cause) {
            listener.onFailure(cause);
        }

        int connectionCount() {
            return connection == null ? 0 : 1;
        }

        URI connectedUrl() {
            return connectedUrl;
        }

        String latestPayload() {
            return connection.payloads.getLast();
        }

        int closeCount() {
            return connection == null ? 0 : connection.closeCount;
        }

        private static final class FakeConnection implements GatewayConnection {
            private final List<String> payloads = new ArrayList<>();
            private int closeCount;

            @Override
            public CompletionStage<Void> sendText(String payload) {
                payloads.add(payload);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> close() {
                closeCount++;
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private static final class ManualRuntimeScheduler implements RuntimeScheduler {
        private final PriorityQueue<Task> tasks = new PriorityQueue<>(
                Comparator.comparingLong(Task::dueNanos).thenComparingLong(Task::order));
        private long nowNanos;
        private long nextOrder;
        private boolean closed;

        @Override
        public Cancellable schedule(Duration delay, Runnable task) {
            if (closed) {
                throw new IllegalStateException("scheduler is closed");
            }
            Task scheduled = new Task(
                    Math.addExact(nowNanos, delay.toNanos()), nextOrder++, task);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        void advance(Duration duration) {
            long target = Math.addExact(nowNanos, duration.toNanos());
            while (!tasks.isEmpty() && tasks.peek().dueNanos <= target) {
                Task task = tasks.remove();
                nowNanos = task.dueNanos;
                if (!task.cancelled) {
                    task.runnable.run();
                }
            }
            nowNanos = target;
        }

        java.util.Optional<Duration> nextDelay() {
            return tasks.stream()
                    .filter(task -> !task.cancelled)
                    .min(Comparator.comparingLong(Task::dueNanos))
                    .map(task -> Duration.ofNanos(task.dueNanos - nowNanos));
        }

        boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
            tasks.clear();
        }

        private static final class Task {
            private final long dueNanos;
            private final long order;
            private final Runnable runnable;
            private boolean cancelled;

            private Task(long dueNanos, long order, Runnable runnable) {
                this.dueNanos = dueNanos;
                this.order = order;
                this.runnable = runnable;
            }

            long dueNanos() {
                return dueNanos;
            }

            long order() {
                return order;
            }
        }
    }

    private static final class QqHttpFixture implements AutoCloseable {
        private final HttpServer server;
        private final int tokenStatus;
        private final String tokenBody;
        private final int gatewayStatus;
        private final String gatewayBody;
        private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
        private final AtomicReference<String> gatewayAuthorization = new AtomicReference<>();

        private QqHttpFixture(int gatewayStatus, String gatewayBody) throws IOException {
            this(
                    200,
                    "{\"access_token\":\"access-token\",\"expires_in\":\"7200\"}",
                    gatewayStatus,
                    gatewayBody);
        }

        private QqHttpFixture(
                int tokenStatus,
                String tokenBody,
                int gatewayStatus,
                String gatewayBody) throws IOException {
            this.tokenStatus = tokenStatus;
            this.tokenBody = tokenBody;
            this.gatewayStatus = gatewayStatus;
            this.gatewayBody = gatewayBody;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", this::token);
            server.createContext("/gateway/bot", this::gateway);
            server.start();
        }

        static QqHttpFixture tokenBusinessError(String tokenBody) throws IOException {
            return new QqHttpFixture(
                    200, tokenBody, 200, gatewayResponse(1, 999, 60_000L));
        }

        private void token(HttpExchange exchange) throws IOException {
            tokenRequestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, tokenStatus, tokenBody);
        }

        private void gateway(HttpExchange exchange) throws IOException {
            gatewayAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, gatewayStatus, gatewayBody);
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        }

        URI uri(String path) {
            return baseUri().resolve(path);
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        String tokenRequestBody() {
            return tokenRequestBody.get();
        }

        String gatewayAuthorization() {
            return gatewayAuthorization.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
