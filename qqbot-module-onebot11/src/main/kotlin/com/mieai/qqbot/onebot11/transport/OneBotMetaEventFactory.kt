package com.mieai.qqbot.onebot11.transport

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import java.time.Clock

class OneBotMetaEventFactory(
    private val objectMapper: ObjectMapper,
    private val supervisor: BotSupervisor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun lifecycle(selfId: Long): ObjectNode = base(selfId).apply {
        put("meta_event_type", "lifecycle")
        put("sub_type", "connect")
    }

    fun heartbeat(botId: BotId, selfId: Long, intervalMs: Int): ObjectNode {
        val online = supervisor.status(botId)?.state == BotRuntimeState.ONLINE
        return base(selfId).apply {
            put("meta_event_type", "heartbeat")
            putObject("status").apply {
                put("online", online)
                put("good", online)
            }
            put("interval", intervalMs)
        }
    }

    private fun base(selfId: Long): ObjectNode = objectMapper.createObjectNode().apply {
        put("time", clock.instant().epochSecond)
        put("self_id", selfId)
        put("post_type", "meta_event")
    }
}
