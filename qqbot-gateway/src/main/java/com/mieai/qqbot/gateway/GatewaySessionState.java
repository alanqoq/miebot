package com.mieai.qqbot.gateway;

/** Observable lifecycle states of one bot Gateway session. */
public enum GatewaySessionState {
    NEW,
    LOADING_SNAPSHOT,
    CONNECTING,
    AWAITING_HELLO,
    IDENTIFYING,
    RESUMING,
    READY,
    BACKING_OFF,
    STOPPED
}
