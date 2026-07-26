package com.mieai.qqbot.runtime.event
fun interface BotGatewayEventSink {
    fun publish(event: BotGatewayEvent)
    companion object { fun noop(): BotGatewayEventSink = BotGatewayEventSink { } }
}
