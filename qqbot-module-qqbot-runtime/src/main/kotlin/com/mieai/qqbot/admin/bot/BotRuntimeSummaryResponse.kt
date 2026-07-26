package com.mieai.qqbot.admin.bot

import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus
import java.time.Instant

class BotRuntimeSummaryResponse(
    totalCount: Int,
    enabledCount: Int,
    connectedCount: Int,
    observedAt: Instant,
    bots: List<BotRuntimeStatusResponse>,
) {
    @get:JsonProperty("totalCount")
    val totalCount = totalCount

    @get:JsonProperty("enabledCount")
    val enabledCount = enabledCount

    @get:JsonProperty("connectedCount")
    val connectedCount = connectedCount

    @get:JsonProperty("observedAt")
    val observedAt = observedAt

    @get:JsonProperty("bots")
    val bots: List<BotRuntimeStatusResponse> = bots.toList()

    init {
        require(totalCount >= 0 && enabledCount >= 0 && connectedCount >= 0) {
            "runtime counts must not be negative"
        }
    }

    companion object {
        fun from(statuses: List<BotRuntimeStatus>, observedAt: Instant): BotRuntimeSummaryResponse {
            val bots = statuses.map(BotRuntimeStatusResponse::from)
            val enabledCount = statuses.count(BotRuntimeStatus::desiredEnabled)
            val connectedCount = statuses.count { status -> status.state == BotRuntimeState.ONLINE }
            return BotRuntimeSummaryResponse(
                statuses.size,
                enabledCount,
                connectedCount,
                observedAt,
                bots,
            )
        }
    }
}
