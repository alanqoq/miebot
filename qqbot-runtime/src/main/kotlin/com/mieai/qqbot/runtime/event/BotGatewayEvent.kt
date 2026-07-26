package com.mieai.qqbot.runtime.event
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.gateway.GatewayDispatch
import java.time.Instant
data class BotGatewayEvent(val botId: BotId, val dispatch: GatewayDispatch, val receivedAt: Instant)
