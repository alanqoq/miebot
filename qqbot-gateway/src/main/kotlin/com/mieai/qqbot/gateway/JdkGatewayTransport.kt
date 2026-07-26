package com.mieai.qqbot.gateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Production QQ Gateway transport backed by the Java 21 HTTP client. */
class JdkGatewayTransport private constructor(
    private val connector: WebSocketConnector,
    private val maxTextCharacters: Int,
    private val normalized: Boolean,
) : GatewayTransport {
    constructor() : this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_MAX_TEXT_CHARACTERS)

    constructor(connectTimeout: Duration, maxTextCharacters: Int) : this(
        connector(connectTimeout),
        requireMaxTextCharacters(maxTextCharacters),
        true,
    )

    constructor(connector: WebSocketConnector, maxTextCharacters: Int) : this(
        connector,
        requireMaxTextCharacters(maxTextCharacters),
        true,
    )

    override fun connect(
        gatewayUrl: URI,
        listener: GatewayTransport.Listener,
    ): CompletionStage<GatewayConnection> {
        val result = CompletableFuture<GatewayConnection>()
        val socketListener = OrderedWebSocketListener(listener, maxTextCharacters)
        val opening = try {
            connector.connect(gatewayUrl, socketListener)
        } catch (exception: RuntimeException) {
            return CompletableFuture.failedFuture(exception)
        }
        opening.whenComplete { socket, throwable ->
            when {
                throwable != null -> {
                    socketListener.discard()
                    result.completeExceptionally(unwrap(throwable))
                }

                socket == null -> {
                    socketListener.discard()
                    result.completeExceptionally(NullPointerException("connector returned a null WebSocket"))
                }

                else -> {
                    result.complete(JdkGatewayConnection(socket))
                    socketListener.publish()
                }
            }
        }
        return result.minimalCompletionStage()
    }

    fun interface WebSocketConnector {
        fun connect(uri: URI, listener: WebSocket.Listener): CompletionStage<WebSocket>
    }

    private class JdkGatewayConnection(private val socket: WebSocket) : GatewayConnection {
        private val closing = AtomicBoolean()
        private var sendTail: CompletionStage<Void> = CompletableFuture.completedFuture(null)
        private var closeStage: CompletionStage<Void>? = null

        @Synchronized
        override fun sendText(payload: String): CompletionStage<Void> {
            if (closing.get()) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("Gateway connection is closing"),
                )
            }
            val sending = sendTail
                .handle { _, _ -> Unit }
                .thenCompose { socket.sendText(payload, true) }
                .thenAccept { }
            sendTail = sending
            return sending
        }

        @Synchronized
        override fun close(): CompletionStage<Void> {
            closeStage?.let { return it }
            closing.set(true)
            val closingStage = sendTail
                .handle { _, _ -> Unit }
                .thenCompose { socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown") }
                .handle { _, throwable ->
                    if (throwable != null) {
                        socket.abort()
                        throw CompletionException(unwrap(throwable))
                    }
                    Unit
                }
                .thenAccept { }
            closeStage = closingStage
            return closingStage
        }
    }

    private class OrderedWebSocketListener(
        private val listener: GatewayTransport.Listener,
        private val maxTextCharacters: Int,
    ) : WebSocket.Listener {
        private val monitor = Any()
        private val text = StringBuilder()
        private val events: Queue<(GatewayTransport.Listener) -> Unit> = ArrayDeque()

        private var published = false
        private var discarded = false
        private var delivering = false
        private var terminalQueued = false

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1L)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            var completed: String? = null
            var oversized: RuntimeException? = null
            synchronized(monitor) {
                if (!terminalQueued && !discarded) {
                    if (text.length.toLong() + data.length > maxTextCharacters) {
                        oversized = IllegalArgumentException(
                            "QQ Gateway text message exceeded the configured limit",
                        )
                        text.setLength(0)
                    } else {
                        text.append(data)
                        if (last) {
                            completed = text.toString()
                            text.setLength(0)
                        }
                    }
                }
            }
            when {
                oversized != null -> {
                    webSocket.abort()
                    val failure = oversized!!
                    enqueueTerminal { target -> target.onFailure(failure) }
                }

                completed != null -> {
                    val payload = completed!!
                    enqueue { target -> target.onText(payload) }
                }
            }
            webSocket.request(1L)
            return CompletableFuture.completedFuture<Void>(null)
        }

        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean,
        ): CompletionStage<*> {
            webSocket.abort()
            enqueueTerminal { target ->
                target.onFailure(IllegalArgumentException("QQ Gateway sent an unsupported binary message"))
            }
            return CompletableFuture.completedFuture<Void>(null)
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
            webSocket.request(1L)
            return webSocket.sendPong(message)
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> {
            webSocket.request(1L)
            return CompletableFuture.completedFuture<Void>(null)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String?,
        ): CompletionStage<*> {
            enqueueTerminal { target -> target.onClosed(statusCode, reason ?: "") }
            return CompletableFuture.completedFuture<Void>(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable?) {
            val failure = requireNotNull(error) { "WebSocket error must not be null" }
            enqueueTerminal { target ->
                target.onFailure(failure)
            }
        }

        fun publish() {
            val shouldDrain = synchronized(monitor) {
                if (discarded) {
                    return
                }
                published = true
                val drain = !delivering && events.isNotEmpty()
                if (drain) {
                    delivering = true
                }
                drain
            }
            if (shouldDrain) {
                drain()
            }
        }

        fun discard() {
            synchronized(monitor) {
                discarded = true
                events.clear()
                text.setLength(0)
            }
        }

        private fun enqueue(event: (GatewayTransport.Listener) -> Unit) {
            enqueue(event, false)
        }

        private fun enqueueTerminal(event: (GatewayTransport.Listener) -> Unit) {
            enqueue(event, true)
        }

        private fun enqueue(event: (GatewayTransport.Listener) -> Unit, terminal: Boolean) {
            val shouldDrain = synchronized(monitor) {
                if (discarded || terminalQueued) {
                    return
                }
                if (terminal) {
                    terminalQueued = true
                }
                events.add(event)
                val drain = published && !delivering
                if (drain) {
                    delivering = true
                }
                drain
            }
            if (shouldDrain) {
                drain()
            }
        }

        private fun drain() {
            while (true) {
                val event = synchronized(monitor) {
                    val queued = events.poll()
                    if (queued == null) {
                        delivering = false
                        return
                    }
                    queued
                }
                try {
                    event(listener)
                } catch (_: RuntimeException) {
                    // Transport observation failures must not corrupt WebSocket demand handling.
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_TEXT_CHARACTERS = 2 * 1024 * 1024

        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        private fun connector(connectTimeout: Duration): WebSocketConnector {
            require(!connectTimeout.isZero && !connectTimeout.isNegative) {
                "connectTimeout must be positive"
            }
            val client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            return WebSocketConnector { uri, listener ->
                client.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(uri, listener)
            }
        }

        private fun requireMaxTextCharacters(value: Int): Int {
            require(value >= 1) { "maxTextCharacters must be positive" }
            return value
        }

        private fun unwrap(throwable: Throwable): Throwable {
            var current = throwable
            while (current is CompletionException && current.cause != null) {
                current = current.cause!!
            }
            return current
        }
    }
}
