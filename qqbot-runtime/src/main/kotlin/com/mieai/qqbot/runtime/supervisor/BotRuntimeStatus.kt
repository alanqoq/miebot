package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import java.time.Instant

data class BotRuntimeStatus(
    val botId: BotId,
    val configurationRevision: BotRevision,
    val desiredEnabled: Boolean,
    val state: BotRuntimeState,
    val stateChangedAt: Instant,
    val startedAt: Instant?,
    val readyAt: Instant?,
    val lastHeartbeatAt: Instant?,
    val lastDispatchAt: Instant?,
    val session: BotSessionSnapshot?,
    val reconnectCount: Long,
    val lastFailure: BotRuntimeFailure?,
    val lastFailureAt: Instant?,
) {
    init {
        require(reconnectCount >= 0) { "reconnectCount must not be negative" }
        require((lastFailure == null) == (lastFailureAt == null)) {
            "lastFailure and lastFailureAt must be present together"
        }
    }
}
