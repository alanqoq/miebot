package com.mieai.qqbot.runtime.event
fun interface BotGatewayEventSource { fun subscribe(listener: BotGatewayEventListener): AutoCloseable }
