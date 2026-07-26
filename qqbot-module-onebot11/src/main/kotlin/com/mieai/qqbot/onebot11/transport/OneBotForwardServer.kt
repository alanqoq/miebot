package com.mieai.qqbot.onebot11.transport

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer

class OneBotForwardServer(
    private val botId: BotId,
    bindAddress: String,
    port: Int,
    private val accessToken: String,
    private val actions: OneBotActionService,
    private val lifecycleEvent: () -> String,
    private val errorListener: (String) -> Unit,
    private val stateListener: () -> Unit,
) : WebSocketServer(InetSocketAddress(bindAddress, port)), AutoCloseable {
    private val started = CountDownLatch(1)
    private val startFailure = AtomicReference<RuntimeException>()

    init {
        setReuseAddr(true)
        setConnectionLostTimeout(30)
        setMaxPendingConnections(64)
        setDaemon(true)
    }

    fun startListening() {
        start()
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                close()
                throw IllegalStateException("Forward WebSocket listener timed out")
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Forward WebSocket startup was interrupted", exception)
        }
        startFailure.get()?.let { throw it }
    }

    @Throws(InvalidDataException::class)
    override fun onWebsocketHandshakeReceivedAsServer(
        connection: WebSocket,
        draft: Draft,
        request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val role = role(request.resourceDescriptor)
        if (role == null || !authorized(request)) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized")
        }
        connection.setAttachment(role)
        return super.onWebsocketHandshakeReceivedAsServer(connection, draft, request)
    }

    override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
        val role: OneBotConnectionRole? = connection.getAttachment()
        if (role?.acceptsEvents() == true) {
            connection.send(lifecycleEvent())
        }
        stateListener()
    }

    override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
        stateListener()
    }

    override fun onMessage(connection: WebSocket, message: String) {
        val role: OneBotConnectionRole? = connection.getAttachment()
        if (role?.acceptsActions() != true) {
            connection.close(CloseFrame.POLICY_VALIDATION, "This endpoint only accepts events")
            return
        }
        if (message.length > MAX_ACTION_CHARACTERS) {
            connection.close(CloseFrame.TOOBIG, "Action frame is too large")
            return
        }
        actions.handle(botId, message).whenComplete { response, failure ->
            if (!connection.isOpen) {
                return@whenComplete
            }
            if (failure != null) {
                connection.close(CloseFrame.UNEXPECTED_CONDITION, "Action processing failed")
            } else {
                connection.send(response)
            }
        }
    }

    override fun onError(connection: WebSocket?, exception: Exception) {
        if (started.count > 0L) {
            startFailure.compareAndSet(
                null,
                IllegalStateException("Unable to bind forward WebSocket listener", exception),
            )
            started.countDown()
        } else {
            errorListener("Forward WebSocket transport failed")
        }
        stateListener()
    }

    override fun onStart() {
        started.countDown()
        stateListener()
    }

    fun publish(event: String) {
        connections.forEach { connection ->
            val role: OneBotConnectionRole? = connection.getAttachment()
            if (connection.isOpen && role?.acceptsEvents() == true) {
                if (connection.hasBufferedData()) {
                    connection.close(CloseFrame.POLICY_VALIDATION, "Event consumer is too slow")
                } else {
                    connection.send(event)
                }
            }
        }
    }

    fun connectionCount(): Int = connections.size

    override fun close() {
        try {
            stop(3_000, "OneBot transport stopped")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun authorized(request: ClientHandshake): Boolean {
        val authorization: String? = request.getFieldValue("Authorization")
        var candidate = if (
            authorization != null && authorization.regionMatches(
                0,
                "Bearer ",
                0,
                7,
                ignoreCase = true,
            )
        ) {
            authorization.substring(7)
        } else {
            null
        }
        if (candidate.isNullOrEmpty()) {
            candidate = queryToken(request.resourceDescriptor)
        }
        return candidate != null && MessageDigest.isEqual(
            accessToken.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        private const val MAX_ACTION_CHARACTERS = 1_048_576

        private fun role(resource: String): OneBotConnectionRole? {
            var path = resource.substringBefore('?')
            if (path.length > 1 && path.endsWith('/')) {
                path = path.dropLast(1)
            }
            return when (path) {
                "/api" -> OneBotConnectionRole.API
                "/event" -> OneBotConnectionRole.EVENT
                "/" -> OneBotConnectionRole.UNIVERSAL
                else -> null
            }
        }

        private fun queryToken(resource: String): String? {
            val query = resource.indexOf('?')
            if (query < 0 || query == resource.length - 1) return null
            resource.substring(query + 1).split('&').forEach { item ->
                val equals = item.indexOf('=')
                val name = if (equals < 0) item else item.substring(0, equals)
                if (name == "access_token") {
                    val value = if (equals < 0) "" else item.substring(equals + 1)
                    return try {
                        URLDecoder.decode(value, StandardCharsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
            return null
        }
    }
}
