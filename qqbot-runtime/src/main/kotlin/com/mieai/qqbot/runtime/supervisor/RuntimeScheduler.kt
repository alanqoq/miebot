package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.gateway.GatewayScheduler

interface RuntimeScheduler : GatewayScheduler, AutoCloseable {
    override fun close()
}
