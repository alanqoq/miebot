package com.mieai.qqbot.gateway

/** Sanitized session-layer failure with a stable reconnect category. */
class GatewaySessionException(
    category: GatewayReconnectCause,
    message: String?,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    private val categoryValue = category

    fun category(): GatewayReconnectCause = categoryValue
}
