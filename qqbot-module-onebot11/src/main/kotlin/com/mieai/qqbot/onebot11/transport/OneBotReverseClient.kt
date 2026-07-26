package com.mieai.qqbot.onebot11.transport

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ServerHandshake

class OneBotReverseClient(
    private val botId: BotId,
    private val url: URI,
    private val selfId: Long,
    private val accessToken: String,
    private val reconnectIntervalMs: Int,
    private val actions: OneBotActionService,
    private val lifecycleEvent: () -> String,
    private val errorListener: (String) -> Unit,
    private val stateListener: () -> Unit,
) : AutoCloseable {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        runnable -> Thread(runnable, "onebot11-reverse-${botIdFragment()}").apply { isDaemon = true }
    }
    private val reconnectScheduled = AtomicBoolean()

    @Volatile
    private var client: Client? = null

    @Volatile
    private var stopped = false

    fun startConnecting() {
        scheduler.execute(::connectNew)
    }

    fun publish(event: String) {
        val current = client
        if (current == null || !current.isOpen) {
            return
        }
        if (current.hasBufferedData()) {
            current.close(CloseFrame.POLICY_VALIDATION, "Event consumer is too slow")
        } else {
            current.send(event)
        }
    }

    fun connected(): Boolean = client?.isOpen == true

    @Synchronized
    private fun connectNew() {
        reconnectScheduled.set(false)
        if (stopped) {
            return
        }
        val previous = client
        if (previous != null && !previous.isClosed) {
            return
        }
        val replacement = Client()
        replacement.setConnectionLostTimeout(30)
        client = replacement
        replacement.connect()
        stateListener()
    }

    private fun scheduleReconnect() {
        if (stopped || !reconnectScheduled.compareAndSet(false, true)) {
            return
        }
        scheduler.schedule(::connectNew, reconnectIntervalMs.toLong(), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        if (stopped) {
            return
        }
        stopped = true
        client?.close(CloseFrame.NORMAL, "OneBot transport stopped")
        scheduler.shutdownNow()
        stateListener()
    }

    private fun botIdFragment(): String {
        val value = botId.toString()
        return value.substring(0, minOf(8, value.length))
    }

    private inner class Client : WebSocketClient(
        url,
        Draft_6455(),
        mapOf(
            "Authorization" to "Bearer $accessToken",
            "X-Self-ID" to selfId.toString(),
            "X-Client-Role" to "Universal",
        ),
        10_000,
    ) {
        override fun onOpen(handshake: ServerHandshake) {
            reconnectScheduled.set(false)
            send(lifecycleEvent())
            stateListener()
        }

        override fun onMessage(message: String) {
            if (message.length > MAX_ACTION_CHARACTERS) {
                close(CloseFrame.TOOBIG, "Action frame is too large")
                return
            }
            actions.handle(botId, message).whenComplete { response, failure ->
                if (!isOpen) return@whenComplete
                if (failure != null) {
                    close(CloseFrame.UNEXPECTED_CONDITION, "Action processing failed")
                } else {
                    send(response)
                }
            }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            stateListener()
            scheduleReconnect()
        }

        override fun onError(exception: Exception) {
            errorListener("Reverse WebSocket connection failed")
            stateListener()
        }
    }

    companion object {
        private const val MAX_ACTION_CHARACTERS = 1_048_576
    }
}
