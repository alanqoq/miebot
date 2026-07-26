package com.mieai.qqbot.gateway

import java.net.URI
import java.util.concurrent.CompletionStage

/** Transport boundary for a text-based QQ Gateway WebSocket. */
fun interface GatewayTransport {
    /**
     * Opens the exact URI returned by QQ. Implementations must not reconstruct or normalize it.
     * Listener callbacks must preserve wire order and start after the returned stage completes.
     */
    fun connect(gatewayUrl: URI, listener: Listener): CompletionStage<GatewayConnection>

    interface Listener {
        fun onText(payload: String)

        fun onClosed(statusCode: Int, reason: String)

        fun onFailure(cause: Throwable)
    }
}
