package com.mieai.qqbot.gateway

import java.time.Duration

/** Non-blocking observation hooks for a Gateway session. */
interface GatewaySessionListener {
    fun onStateChanged(previous: GatewaySessionState, current: GatewaySessionState) {}

    fun onReady(snapshot: GatewaySessionSnapshot) {}

    /**
     * Gives the application a chance to durably accept a dispatch before the session advances its
     * resumable sequence. A false result reconnects while retaining the previous sequence.
     */
    fun acceptDispatch(dispatch: GatewayDispatch): Boolean = true

    fun onDispatch(dispatch: GatewayDispatch) {}

    fun onUnknownOpcode(opcode: Int, rawPayload: String) {}

    fun onHeartbeatSent(sequence: Long?) {}

    fun onHeartbeatAcknowledged() {}

    fun onReconnectScheduled(attempt: Int, cause: GatewayReconnectCause, delay: Duration) {}

    fun onFailure(cause: Throwable) {}

    fun onStopped(decision: GatewayCloseDecision) {}

    companion object {
        fun none(): GatewaySessionListener = object : GatewaySessionListener {}
    }
}
