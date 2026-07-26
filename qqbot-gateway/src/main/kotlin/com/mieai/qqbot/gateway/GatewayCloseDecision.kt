package com.mieai.qqbot.gateway

/** Classified action for a WebSocket close. */
data class GatewayCloseDecision(
    val statusCode: Int,
    val disposition: GatewayCloseDisposition,
    val reconnectCause: GatewayReconnectCause,
)
