package com.mieai.qqbot.gateway

/** Session action selected for a WebSocket close code. */
enum class GatewayCloseDisposition {
    RESUME,
    IDENTIFY,
    STOP,
}
