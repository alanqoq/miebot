package com.mieai.qqbot.gateway

import java.time.Duration

internal class RecordingGatewayListener : GatewaySessionListener {
    private val statesValue = mutableListOf<GatewaySessionState>()
    private val readySnapshotsValue = mutableListOf<GatewaySessionSnapshot>()
    private val acceptanceAttemptsValue = mutableListOf<GatewayDispatch>()
    private val dispatchesValue = mutableListOf<GatewayDispatch>()
    private val unknownOpcodesValue = mutableListOf<Int>()
    private val heartbeatSequencesValue = mutableListOf<Long?>()
    private val reconnectsValue = mutableListOf<ScheduledReconnect>()
    private val failuresValue = mutableListOf<Throwable>()
    private val stopsValue = mutableListOf<GatewayCloseDecision>()
    private var heartbeatAcknowledgementsValue = 0
    private var acceptDispatch = true
    private var dispatchAcceptanceFailure: RuntimeException? = null

    override fun onStateChanged(previous: GatewaySessionState, current: GatewaySessionState) {
        statesValue += current
    }

    override fun onReady(snapshot: GatewaySessionSnapshot) { readySnapshotsValue += snapshot }

    override fun acceptDispatch(dispatch: GatewayDispatch): Boolean {
        acceptanceAttemptsValue += dispatch
        dispatchAcceptanceFailure?.let { throw it }
        return acceptDispatch
    }

    override fun onDispatch(dispatch: GatewayDispatch) { dispatchesValue += dispatch }

    override fun onUnknownOpcode(opcode: Int, rawPayload: String) { unknownOpcodesValue += opcode }

    override fun onHeartbeatSent(sequence: Long?) { heartbeatSequencesValue += sequence }

    override fun onHeartbeatAcknowledged() { heartbeatAcknowledgementsValue++ }

    override fun onReconnectScheduled(attempt: Int, cause: GatewayReconnectCause, delay: Duration) {
        reconnectsValue += ScheduledReconnect(attempt, cause, delay)
    }

    override fun onFailure(cause: Throwable) { failuresValue += cause }

    override fun onStopped(decision: GatewayCloseDecision) { stopsValue += decision }

    fun states(): List<GatewaySessionState> = statesValue.toList()
    fun readySnapshots(): List<GatewaySessionSnapshot> = readySnapshotsValue.toList()
    fun dispatches(): List<GatewayDispatch> = dispatchesValue.toList()
    fun acceptanceAttempts(): List<GatewayDispatch> = acceptanceAttemptsValue.toList()
    fun unknownOpcodes(): List<Int> = unknownOpcodesValue.toList()
    fun heartbeatSequences(): List<Long?> = heartbeatSequencesValue.toList()
    fun heartbeatAcknowledgements(): Int = heartbeatAcknowledgementsValue
    fun reconnects(): List<ScheduledReconnect> = reconnectsValue.toList()
    fun failures(): List<Throwable> = failuresValue.toList()
    fun stops(): List<GatewayCloseDecision> = stopsValue.toList()

    fun rejectDispatches() { acceptDispatch = false }
    fun failDispatchAcceptance() { dispatchAcceptanceFailure = IllegalStateException("inbox unavailable") }

    data class ScheduledReconnect(
        val attempt: Int,
        val cause: GatewayReconnectCause,
        val delay: Duration,
    )
}
