package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.gateway.GatewayDispatch

interface BotRuntimeObserver {
    fun onStateChanged(state: BotRuntimeState) = Unit

    fun onReady(snapshot: BotSessionSnapshot) = Unit

    fun onHeartbeatAcknowledged(sequence: Long?) = Unit

    fun acceptDispatch(dispatch: GatewayDispatch): Boolean = true

    fun onDispatch(dispatch: GatewayDispatch) = Unit

    fun onReconnectScheduled() = Unit

    fun onFailure(failure: BotRuntimeFailure) = Unit
}
