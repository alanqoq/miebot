package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.runtime.supervisor.BotSessionSnapshot

data class BotRuntimeSessionResponse(val id: String, val sequence: Long) {
    companion object {
        fun from(snapshot: BotSessionSnapshot): BotRuntimeSessionResponse =
            BotRuntimeSessionResponse(snapshot.sessionId, snapshot.sequence)
    }
}
