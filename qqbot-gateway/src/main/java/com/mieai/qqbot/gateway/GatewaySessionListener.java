package com.mieai.qqbot.gateway;

import java.time.Duration;

/** Non-blocking observation hooks for a Gateway session. */
public interface GatewaySessionListener {
    default void onStateChanged(GatewaySessionState previous, GatewaySessionState current) {}

    default void onReady(GatewaySessionSnapshot snapshot) {}

    /**
     * Gives the application a chance to durably accept a dispatch before the session advances its
     * resumable sequence. Implementations must return promptly; a false result causes the session
     * to reconnect while retaining the previous sequence. The default accepts every dispatch.
     */
    default boolean acceptDispatch(GatewayDispatch dispatch) {
        return true;
    }

    default void onDispatch(GatewayDispatch dispatch) {}

    default void onUnknownOpcode(int opcode, String rawPayload) {}

    default void onHeartbeatSent(Long sequence) {}

    default void onHeartbeatAcknowledged() {}

    default void onReconnectScheduled(
            int attempt, GatewayReconnectCause cause, Duration delay) {}

    default void onFailure(Throwable cause) {}

    default void onStopped(GatewayCloseDecision decision) {}

    static GatewaySessionListener none() {
        return new GatewaySessionListener() {};
    }
}
