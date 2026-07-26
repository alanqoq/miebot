package com.mieai.qqbot.gateway

/** Why a Gateway connection is being retried. */
enum class GatewayReconnectCause {
    SERVER_RECONNECT,
    INVALID_SESSION,
    ACK_TIMEOUT,
    CONNECTION_CLOSED,
    RATE_LIMITED,
    AUTHENTICATION_FAILURE,
    TRANSPORT_FAILURE,
    PROTOCOL_FAILURE,
    SEND_FAILURE,
    DISPATCH_REJECTED,
}
