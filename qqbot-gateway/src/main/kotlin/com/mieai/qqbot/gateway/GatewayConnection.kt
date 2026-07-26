package com.mieai.qqbot.gateway

import java.util.concurrent.CompletionStage

/** One established Gateway WebSocket connection. */
interface GatewayConnection {
    fun sendText(payload: String): CompletionStage<Void>

    fun close(): CompletionStage<Void>
}
