package com.mieai.qqbot.gateway

/** Observable lifecycle states of one bot Gateway session. */
enum class GatewaySessionState {
    NEW,
    LOADING_SNAPSHOT,
    CONNECTING,
    AWAITING_HELLO,
    IDENTIFYING,
    RESUMING,
    READY,
    BACKING_OFF,
    STOPPED,
}
