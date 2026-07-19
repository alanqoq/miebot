package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.gateway.GatewayDispatch;

/**
 * Non-blocking callbacks from one managed bot runtime.
 *
 * <p>State callbacks may be invoked while a Gateway session lock is held and must only publish
 * state. {@link #acceptDispatch(GatewayDispatch)} is the explicit durable-ingress exception and
 * must perform only bounded I/O before returning.
 */
public interface BotRuntimeObserver {
    default void onStateChanged(BotRuntimeState state) {}

    default void onReady(BotSessionSnapshot snapshot) {}

    default void onHeartbeatAcknowledged() {}

    default void onHeartbeatAcknowledged(long sequence) {
        onHeartbeatAcknowledged();
    }

    /**
     * Synchronously accepts a dispatch before Gateway Resume state advances. Implementations may
     * perform a bounded durable write here; returning {@code false} asks the Gateway session to
     * reconnect with the previous sequence. Retiring generations should return {@code true} so a
     * stop race does not create a new connection.
     */
    default boolean acceptDispatch(GatewayDispatch dispatch) {
        return true;
    }

    default void onDispatch(GatewayDispatch dispatch) {
        onDispatch(dispatch.sequence());
    }

    /** @deprecated override {@link #onDispatch(GatewayDispatch)} to retain the full event. */
    @Deprecated
    default void onDispatch(long sequence) {}

    default void onReconnectScheduled() {}

    default void onFailure(BotRuntimeFailure failure) {}
}
