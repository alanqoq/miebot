package com.mieai.qqbot.onebot11.transport

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.config.ResolvedOneBot11Config
import com.mieai.qqbot.onebot11.protocol.OneBotActionService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OneBotBotRuntime(
    private val objectMapper: ObjectMapper,
    private val resolved: ResolvedOneBot11Config,
    private val selfId: Long,
    private val metaEvents: OneBotMetaEventFactory,
    private val actions: OneBotActionService,
) : AutoCloseable {
    private val heartbeat: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        runnable ->
        Thread(
            runnable,
            "onebot11-heartbeat-${botId().toString().substring(0, 8)}",
        ).apply { isDaemon = true }
    }

    @Volatile
    private var forward: OneBotForwardServer? = null

    @Volatile
    private var reverse: OneBotReverseClient? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var closed = false

    fun start() {
        val config = resolved.config
        try {
            if (config.forwardEnabled) {
                forward = OneBotForwardServer(
                    botId(),
                    config.forwardBindAddress,
                    requireNotNull(config.forwardPort),
                    resolved.accessToken,
                    actions,
                    { encode(metaEvents.lifecycle(selfId)) },
                    ::recordError,
                    {},
                ).also(OneBotForwardServer::startListening)
            }
            if (config.reverseEnabled) {
                reverse = OneBotReverseClient(
                    botId(),
                    requireNotNull(config.reverseUrl),
                    selfId,
                    resolved.accessToken,
                    config.reconnectIntervalMs,
                    actions,
                    { encode(metaEvents.lifecycle(selfId)) },
                    ::recordError,
                    {},
                ).also(OneBotReverseClient::startConnecting)
            }
            if (config.heartbeatEnabled) {
                heartbeat.scheduleAtFixedRate(
                    {
                        publish(
                            metaEvents.heartbeat(botId(), selfId, config.heartbeatIntervalMs),
                        )
                    },
                    config.heartbeatIntervalMs.toLong(),
                    config.heartbeatIntervalMs.toLong(),
                    TimeUnit.MILLISECONDS,
                )
            }
        } catch (exception: RuntimeException) {
            close()
            throw exception
        }
    }

    fun botId(): BotId = resolved.config.botId

    fun revision(): Long = resolved.config.revision

    fun publish(event: ObjectNode) {
        publish(encode(event))
    }

    fun status(): OneBotTransportStatus {
        val currentForward = forward
        val currentReverse = reverse
        val forwardListening = currentForward != null && !closed
        val forwardConnections = currentForward?.connectionCount() ?: 0
        val reverseConnected = currentReverse?.connected() == true
        val state = when {
            closed -> "STOPPED"
            reverseConnected || forwardListening -> "RUNNING"
            else -> "CONNECTING"
        }
        return OneBotTransportStatus(
            state,
            forwardListening,
            forwardConnections,
            reverseConnected,
            lastError,
        )
    }

    private fun publish(event: String) {
        if (closed) return
        forward?.publish(event)
        reverse?.publish(event)
    }

    private fun encode(event: ObjectNode): String = try {
        objectMapper.writeValueAsString(event)
    } catch (exception: Exception) {
        throw IllegalStateException("Unable to encode OneBot event", exception)
    }

    private fun recordError(error: String) {
        lastError = error
    }

    override fun close() {
        if (closed) return
        closed = true
        heartbeat.shutdownNow()
        val currentReverse = reverse
        reverse = null
        currentReverse?.close()
        val currentForward = forward
        forward = null
        currentForward?.close()
    }
}
