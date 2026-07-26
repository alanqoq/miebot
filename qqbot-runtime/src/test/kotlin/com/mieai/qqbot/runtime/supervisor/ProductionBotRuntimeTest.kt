package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.client.AccessToken
import com.mieai.qqbot.client.BotCredentials
import com.mieai.qqbot.client.GatewayBotInfo
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.SessionStartLimit
import com.mieai.qqbot.client.TokenProvider
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.gateway.GatewayBackoffStrategy
import com.mieai.qqbot.gateway.GatewayConnection
import com.mieai.qqbot.gateway.GatewayDispatch
import com.mieai.qqbot.gateway.GatewayScheduler
import com.mieai.qqbot.gateway.GatewaySnapshotStore
import com.mieai.qqbot.gateway.GatewayTransport
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.security.AesGcmAppSecretCipher
import com.mieai.qqbot.runtime.security.AppSecret
import com.mieai.qqbot.runtime.security.AppSecretBinding
import com.mieai.qqbot.runtime.security.StaticKeyProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProductionBotRuntimeTest {
    @Test
    fun `factory decrypts credential uses real QQ clients and publishes Gateway state`() {
        QqHttpFixture(200, gatewayResponse(2, 999, 60_000L)).use { qq ->
            val stored = encryptedBot(ShardSpec.single())
            val transport = FakeGatewayTransport()
            val scheduler = ManualRuntimeScheduler()
            val observer = RecordingObserver()
            val factory = factory(qq, transport, scheduler)

            val runtime = factory.create(stored, observer)
            runtime.start().toCompletableFuture().join()

            assertThat(qq.tokenRequestBody())
                .contains("\"appId\":\"1905208810\"")
                .contains("\"clientSecret\":\"factory-secret\"")
            assertThat(qq.gatewayAuthorization()).isEqualTo("QQBot access-token")
            assertThat(transport.connectedUrl()).isEqualTo(GATEWAY_URL)
            assertThat(observer.states())
                .containsSubsequence(
                    BotRuntimeState.STARTING,
                    BotRuntimeState.DISCOVERING,
                    BotRuntimeState.CONNECTING,
                )

            transport.emit(hello())
            assertThat(transport.latestPayload())
                .contains("\"op\":2")
                .contains("\"token\":\"QQBot access-token\"")
                .contains("\"intents\":33554432")

            transport.emit(ready(1L))
            transport.emit("{\"op\":1}")
            transport.emit("{\"op\":11}")
            val dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event-runtime-1\",\"d\":{\"content\":\"hello\"}}"
            transport.emit(dispatch)

            assertThat(observer.states()).contains(BotRuntimeState.AUTHENTICATING, BotRuntimeState.ONLINE)
            assertThat(observer.snapshots()).containsExactly(BotSessionSnapshot("session-a", 1L))
            assertThat(observer.heartbeatSequences()).containsExactly(1L)
            assertThat(observer.dispatchSequences()).containsExactly(2L)
            assertThat(observer.dispatches()).containsExactly(
                GatewayDispatch(2L, "C2C_MESSAGE_CREATE", dispatch, "event-runtime-1", "session-a"),
            )

            runtime.stop().toCompletableFuture().join()

            assertThat(transport.closeCount()).isEqualTo(1)
            assertThat(scheduler.closed()).isTrue()
            assertThat(observer.states().last()).isEqualTo(BotRuntimeState.STOPPED)
        }
    }

    @Test
    fun `waits for session limit reset without opening Gateway or tight looping`() {
        val definition = definition(ShardSpec.single())
        val credentials = credentials(definition)
        val discoveries = AtomicInteger()
        val discovery = GatewayDiscovery {
            discoveries.incrementAndGet()
            CompletableFuture.completedFuture(
                GatewayBotInfo(
                    GATEWAY_URL,
                    1,
                    SessionStartLimit(1000, 0, Duration.ofSeconds(12), 1),
                ),
            )
        }
        val transport = FakeGatewayTransport()
        val scheduler = ManualRuntimeScheduler()
        val observer = RecordingObserver()
        val runtime = runtime(definition, credentials, discovery, transport, scheduler, observer)

        assertThatThrownBy { runtime.start().toCompletableFuture().join() }
            .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt")

        assertThat(discoveries).hasValue(1)
        assertThat(transport.connectionCount()).isZero()
        assertThat(observer.failures()).containsExactly(
            BotRuntimeFailure(
                "GATEWAY_SESSION_LIMITED",
                "QQ has temporarily exhausted this bot's Gateway session creation allowance",
                true,
            ),
        )
        assertThat(observer.states().last()).isEqualTo(BotRuntimeState.RECONNECTING)
        assertThat(scheduler.nextDelay()).isEqualTo(Duration.ofSeconds(12))

        scheduler.advance(Duration.ofSeconds(11))
        assertThat(discoveries).hasValue(1)
        scheduler.advance(Duration.ofSeconds(1))
        assertThat(discoveries).hasValue(2)

        runtime.stop().toCompletableFuture().join()
        assertThat(credentials.appSecret.isDestroyed).isTrue()
    }

    @Test
    fun `rejects shard count above QQ recommendation before identify`() {
        val definition = definition(ShardSpec(0, 2))
        val credentials = credentials(definition)
        val discovery = GatewayDiscovery {
            CompletableFuture.completedFuture(
                GatewayBotInfo(
                    GATEWAY_URL,
                    1,
                    SessionStartLimit(1000, 999, Duration.ofHours(1), 1),
                ),
            )
        }
        val transport = FakeGatewayTransport()
        val scheduler = ManualRuntimeScheduler()
        val observer = RecordingObserver()
        val runtime = runtime(definition, credentials, discovery, transport, scheduler, observer)

        assertThatThrownBy { runtime.start().toCompletableFuture().join() }
            .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt")

        assertThat(transport.connectionCount()).isZero()
        assertThat(observer.failures()).containsExactly(
            BotRuntimeFailure(
                "GATEWAY_SHARD_CONFIGURATION_INVALID",
                "The configured shard count exceeds the count recommended by QQ",
                false,
            ),
        )
        assertThat(observer.states().last()).isEqualTo(BotRuntimeState.FAILED)
        assertThat(scheduler.nextDelay()).isNull()

        runtime.stop().toCompletableFuture().join()
    }

    @Test
    fun `maps permanent Gateway close without exposing wire reason`() {
        val definition = definition(ShardSpec.single())
        val credentials = credentials(definition)
        val discovery = gatewayDiscovery()
        val transport = FakeGatewayTransport()
        val scheduler = ManualRuntimeScheduler()
        val observer = RecordingObserver()
        val runtime = runtime(definition, credentials, discovery, transport, scheduler, observer)
        runtime.start().toCompletableFuture().join()
        transport.emit(hello())
        transport.emit(ready(1L))

        transport.closeFromServer(4014, "remote-secret-detail")

        assertThat(observer.failures().last()).isEqualTo(
            BotRuntimeFailure(
                "GATEWAY_INTENTS_REJECTED",
                "QQ rejected the configured Gateway event permissions",
                false,
            ),
        )
        assertThat(observer.failures().last().toString()).doesNotContain("remote-secret-detail")
        assertThat(observer.states().last()).isEqualTo(BotRuntimeState.FAILED)

        runtime.stop().toCompletableFuture().join()
        assertThat(credentials.appSecret.isDestroyed).isTrue()
        assertThat(scheduler.closed()).isTrue()
    }

    @Test
    fun `keeps Gateway internal reconnect after initial transport failure`() {
        val definition = definition(ShardSpec.single())
        val credentials = credentials(definition)
        val discovery = gatewayDiscovery()
        val transport = FakeGatewayTransport()
        transport.failNextConnect()
        val scheduler = ManualRuntimeScheduler()
        val observer = RecordingObserver()
        val runtime = runtime(definition, credentials, discovery, transport, scheduler, observer)

        assertThatThrownBy { runtime.start().toCompletableFuture().join() }
            .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt")

        assertThat(transport.connectionCount()).isZero()
        assertThat(observer.states().last()).isEqualTo(BotRuntimeState.RECONNECTING)
        assertThat(observer.failures().last().code).isEqualTo("GATEWAY_TRANSPORT_FAILURE")
        assertThat(scheduler.nextDelay()).isEqualTo(Duration.ofSeconds(1))
        assertThat(credentials.appSecret.isDestroyed).isFalse()

        scheduler.advance(Duration.ofSeconds(1))
        assertThat(transport.connectionCount()).isEqualTo(1)
        transport.emit(hello())
        transport.emit(ready(1L))

        assertThat(observer.states().last()).isEqualTo(BotRuntimeState.ONLINE)
        assertThat(observer.snapshots()).containsExactly(BotSessionSnapshot("session-a", 1L))

        runtime.stop().toCompletableFuture().join()
    }

    @Test
    fun `stop fences callbacks and closes credentials and scheduler before returning`() {
        val definition = definition(ShardSpec.single())
        val credentials = credentials(definition)
        val transport = FakeGatewayTransport()
        val scheduler = ManualRuntimeScheduler()
        val observer = RecordingObserver()
        val runtime = runtime(definition, credentials, gatewayDiscovery(), transport, scheduler, observer)
        runtime.start().toCompletableFuture().join()
        transport.emit(hello())
        transport.emit(ready(1L))

        val stopping = runtime.stop()

        assertThat(credentials.appSecret.isDestroyed).isTrue()
        assertThat(scheduler.closed()).isTrue()
        stopping.toCompletableFuture().join()
        val stoppedStates = observer.states()
        val stoppedFailures = observer.failures()
        val stoppedDispatches = observer.dispatchSequences()

        transport.emit("{\"op\":0,\"s\":9,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{}}")
        transport.closeFromServer(4014, "late-close")
        transport.failFromServer(IOException("late-failure"))

        assertThat(observer.states()).isEqualTo(stoppedStates)
        assertThat(observer.failures()).isEqualTo(stoppedFailures)
        assertThat(observer.dispatchSequences()).isEqualTo(stoppedDispatches)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `maps remote authentication error to stable failure`(status: Int) {
        val remoteBody = "{\"code\":11241,\"message\":\"remote-sensitive-detail\"}"
        QqHttpFixture(status, remoteBody).use { qq ->
            val stored = encryptedBot(ShardSpec.single())
            val transport = FakeGatewayTransport()
            val scheduler = ManualRuntimeScheduler()
            val observer = RecordingObserver()
            val runtime = factory(qq, transport, scheduler).create(stored, observer)

            assertThatThrownBy { runtime.start().toCompletableFuture().join() }
                .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt")

            val failure = observer.failures().last()
            assertThat(failure).isEqualTo(
                BotRuntimeFailure("QQ_AUTHENTICATION_REJECTED", "QQ rejected the bot credentials", false),
            )
            assertThat(failure.toString())
                .doesNotContain("remote-sensitive-detail")
                .doesNotContain(qq.baseUri().toString())
            assertThat(observer.states().last()).isEqualTo(BotRuntimeState.FAILED)

            runtime.stop().toCompletableFuture().join()
        }
    }

    @Test
    fun `maps token HTTP 200 business error to non-retryable authentication failure`() {
        QqHttpFixture.tokenBusinessError(
            "{\"code\":11241,\"message\":\"factory-secret remote-sensitive-detail\"}",
        ).use { qq ->
            val stored = encryptedBot(ShardSpec.single())
            val transport = FakeGatewayTransport()
            val scheduler = ManualRuntimeScheduler()
            val observer = RecordingObserver()
            val runtime = factory(qq, transport, scheduler).create(stored, observer)

            assertThatThrownBy { runtime.start().toCompletableFuture().join() }
                .hasRootCauseMessage("The bot runtime did not complete its initial connection attempt")

            val failure = observer.failures().last()
            assertThat(failure).isEqualTo(
                BotRuntimeFailure("QQ_AUTHENTICATION_REJECTED", "QQ rejected the bot credentials", false),
            )
            assertThat(failure.toString())
                .doesNotContain("factory-secret")
                .doesNotContain("remote-sensitive-detail")
            assertThat(observer.states().last()).isEqualTo(BotRuntimeState.FAILED)
            assertThat(transport.connectionCount()).isZero()
            assertThat(scheduler.nextDelay()).isNull()
            assertThat(qq.gatewayAuthorization()).isNull()

            runtime.stop().toCompletableFuture().join()
        }
    }

    private fun factory(
        qq: QqHttpFixture,
        transport: FakeGatewayTransport,
        scheduler: ManualRuntimeScheduler,
    ): ProductionBotRuntimeFactory {
        val options = QqClientOptions(
            tokenEndpoint = qq.uri("/token"),
            openApiBaseUri = qq.baseUri(),
            requestTimeout = Duration.ofSeconds(2),
        )
        return ProductionBotRuntimeFactory(
            cipher(),
            { options },
            { GatewaySnapshotStore.none() },
            gatewayTransport = transport,
            schedulerFactory = { scheduler },
            backoffStrategy = GatewayBackoffStrategy.fixed(Duration.ofSeconds(1)),
        )
    }

    private fun runtime(
        definition: BotDefinition,
        credentials: BotCredentials,
        discovery: GatewayDiscovery,
        transport: FakeGatewayTransport,
        scheduler: ManualRuntimeScheduler,
        observer: RecordingObserver,
    ): ProductionBotRuntime {
        val tokenProvider = object : TokenProvider {
            override fun getAccessToken() = CompletableFuture.completedFuture(
                AccessToken.of("access-token", NOW.plus(Duration.ofHours(1))),
            )
        }
        return ProductionBotRuntime(
            definition,
            credentials,
            discovery,
            tokenProvider,
            transport,
            GatewaySnapshotStore.none(),
            scheduler,
            GatewayBackoffStrategy.fixed(Duration.ofSeconds(1)),
            observer,
        )
    }

    private fun gatewayDiscovery() = GatewayDiscovery {
        CompletableFuture.completedFuture(
            GatewayBotInfo(
                GATEWAY_URL,
                1,
                SessionStartLimit(1000, 999, Duration.ofHours(1), 1),
            ),
        )
    }

    private fun encryptedBot(shardSpec: ShardSpec): StoredBot {
        val definition = definition(shardSpec)
        val cipher = cipher()
        val encrypted = AppSecret.of("factory-secret").use { secret ->
            cipher.encrypt(secret, AppSecretBinding(definition.id, definition.appId, definition.environment))
        }
        return StoredBot(definition, encrypted)
    }

    private fun cipher(): AesGcmAppSecretCipher {
        val key = ByteArray(32) { 0x5a.toByte() }
        return try {
            AesGcmAppSecretCipher(StaticKeyProvider.configured("runtime-test-key", key))
        } finally {
            key.fill(0)
        }
    }

    private fun definition(shardSpec: ShardSpec) = BotDefinition(
        BotId.of(UUID.fromString("3d5187c3-f958-4b8f-babb-040bcf641d42")),
        "Production Bot",
        QqAppId.of("1905208810"),
        BotEnvironment.PRODUCTION,
        GatewayIntents.of(1L shl 25),
        shardSpec,
        true,
        BotRevision.initial(),
        NOW,
        NOW,
    )

    private fun credentials(definition: BotDefinition) =
        BotCredentials(definition.appId, com.mieai.qqbot.client.AppSecret.of("client-secret"))

    private fun hello() = "{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}"

    private fun ready(sequence: Long) =
        "{\"op\":0,\"s\":$sequence,\"t\":\"READY\",\"d\":{\"version\":1,\"session_id\":\"session-a\",\"user\":{\"id\":\"6158788878435714165\",\"username\":\"Gateway Bot\",\"bot\":true},\"shard\":[0,1]}}"

    private class RecordingObserver : BotRuntimeObserver {
        private val states = mutableListOf<BotRuntimeState>()
        private val snapshots = mutableListOf<BotSessionSnapshot>()
        private val heartbeatSequences = mutableListOf<Long>()
        private val dispatchSequences = mutableListOf<Long>()
        private val dispatches = mutableListOf<GatewayDispatch>()
        private val failures = mutableListOf<BotRuntimeFailure>()

        override fun onStateChanged(state: BotRuntimeState) {
            states += state
        }

        override fun onReady(snapshot: BotSessionSnapshot) {
            snapshots += snapshot
        }

        override fun onHeartbeatAcknowledged(sequence: Long?) {
            sequence?.let(heartbeatSequences::add)
        }

        override fun onDispatch(dispatch: GatewayDispatch) {
            dispatches += dispatch
            dispatchSequences += dispatch.sequence
        }

        override fun onFailure(failure: BotRuntimeFailure) {
            failures += failure
        }

        fun states() = states.toList()
        fun snapshots() = snapshots.toList()
        fun heartbeatSequences() = heartbeatSequences.toList()
        fun dispatchSequences() = dispatchSequences.toList()
        fun dispatches() = dispatches.toList()
        fun failures() = failures.toList()
    }

    private class FakeGatewayTransport : GatewayTransport {
        private var listener: GatewayTransport.Listener? = null
        private var connection: FakeConnection? = null
        private var connectedUrl: URI? = null
        private var failNextConnect = false

        override fun connect(gatewayUrl: URI, listener: GatewayTransport.Listener): CompletionStage<GatewayConnection> {
            connectedUrl = gatewayUrl
            this.listener = listener
            if (failNextConnect) {
                failNextConnect = false
                return CompletableFuture.failedFuture(IOException("initial transport failure"))
            }
            val established: GatewayConnection = FakeConnection().also { connection = it }
            return CompletableFuture.completedFuture(established)
        }

        fun failNextConnect() {
            failNextConnect = true
        }

        fun emit(payload: String) {
            listener!!.onText(payload)
        }

        fun closeFromServer(statusCode: Int, reason: String) {
            listener!!.onClosed(statusCode, reason)
        }

        fun failFromServer(cause: Throwable) {
            listener!!.onFailure(cause)
        }

        fun connectionCount() = if (connection == null) 0 else 1
        fun connectedUrl() = connectedUrl
        fun latestPayload() = connection!!.payloads.last()
        fun closeCount() = connection?.closeCount ?: 0

        private class FakeConnection : GatewayConnection {
            val payloads = mutableListOf<String>()
            var closeCount = 0

            override fun sendText(payload: String): CompletionStage<Void> {
                payloads += payload
                return CompletableFuture.completedFuture(null)
            }

            override fun close(): CompletionStage<Void> {
                closeCount++
                return CompletableFuture.completedFuture(null)
            }
        }
    }

    private class ManualRuntimeScheduler : RuntimeScheduler {
        private val tasks = PriorityQueue(compareBy<Task> { it.dueNanos }.thenBy { it.order })
        private var nowNanos = 0L
        private var nextOrder = 0L
        private var closed = false

        override fun schedule(delay: Duration, task: () -> Unit): GatewayScheduler.Cancellable {
            check(!closed) { "scheduler is closed" }
            val scheduled = Task(Math.addExact(nowNanos, delay.toNanos()), nextOrder++, task)
            tasks += scheduled
            return GatewayScheduler.Cancellable { scheduled.cancelled = true }
        }

        fun advance(duration: Duration) {
            val target = Math.addExact(nowNanos, duration.toNanos())
            while (tasks.isNotEmpty() && tasks.peek().dueNanos <= target) {
                val task = tasks.remove()
                nowNanos = task.dueNanos
                if (!task.cancelled) task.action()
            }
            nowNanos = target
        }

        fun nextDelay() = tasks.asSequence()
            .filterNot { it.cancelled }
            .minByOrNull { it.dueNanos }
            ?.let { Duration.ofNanos(it.dueNanos - nowNanos) }

        fun closed() = closed

        override fun close() {
            closed = true
            tasks.clear()
        }

        private class Task(
            val dueNanos: Long,
            val order: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class QqHttpFixture(
        private val gatewayStatus: Int,
        private val gatewayBody: String,
        private val tokenStatus: Int = 200,
        private val tokenBody: String = "{\"access_token\":\"access-token\",\"expires_in\":\"7200\"}",
    ) : AutoCloseable {
        private val server: HttpServer
        private val tokenRequestBody = AtomicReference<String?>()
        private val gatewayAuthorization = AtomicReference<String?>()

        init {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/token") { exchange -> token(exchange) }
            server.createContext("/gateway/bot") { exchange -> gateway(exchange) }
            server.start()
        }

        private fun token(exchange: HttpExchange) {
            tokenRequestBody.set(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            respond(exchange, tokenStatus, tokenBody)
        }

        private fun gateway(exchange: HttpExchange) {
            gatewayAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, gatewayStatus, gatewayBody)
        }

        fun uri(path: String) = baseUri().resolve(path)
        fun baseUri() = URI.create("http://127.0.0.1:${server.address.port}/")
        fun tokenRequestBody() = tokenRequestBody.get()
        fun gatewayAuthorization() = gatewayAuthorization.get()

        override fun close() {
            server.stop(0)
        }

        companion object {
            fun respond(exchange: HttpExchange, status: Int, body: String) {
                val encoded = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, encoded.size.toLong())
                exchange.responseBody.write(encoded)
                exchange.close()
            }
            fun tokenBusinessError(tokenBody: String) = QqHttpFixture(
                gatewayStatus = 200,
                gatewayBody = gatewayResponse(1, 999, 60_000L),
                tokenBody = tokenBody,
            )
        }
    }

    private companion object {
        val GATEWAY_URL: URI = URI.create("wss://gateway.example/websocket")
        val NOW: Instant = Instant.parse("2026-07-18T00:00:00Z")

        fun gatewayResponse(shards: Int, remaining: Int, resetAfterMillis: Long) =
            "{\"url\":\"$GATEWAY_URL\",\"shards\":$shards,\"session_start_limit\":{\"total\":1000,\"remaining\":$remaining,\"reset_after\":$resetAfterMillis,\"max_concurrency\":1}}"
    }
}
