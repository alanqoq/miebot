package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.runtime.supervisor.BotRuntimeFailure
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus
import java.time.Instant
import java.util.UUID

data class BotRuntimeStatusResponse(
    val botId: UUID,
    val configurationRevision: Long,
    val enabled: Boolean,
    val state: String,
    val stateChangedAt: Instant,
    val connectedAt: Instant?,
    val lastHeartbeatAt: Instant?,
    val lastDispatchAt: Instant?,
    val reconnectCount: Long,
    val session: BotRuntimeSessionResponse?,
    val lastError: BotRuntimeErrorResponse?,
) {
    companion object {
        fun from(status: BotRuntimeStatus): BotRuntimeStatusResponse {
            val lastError = status.lastFailure?.let { failure ->
                error(failure, requireNotNull(status.lastFailureAt))
            }
            return BotRuntimeStatusResponse(
                status.botId.value,
                status.configurationRevision.value,
                status.desiredEnabled,
                status.state.name,
                status.stateChangedAt,
                status.readyAt,
                status.lastHeartbeatAt,
                status.lastDispatchAt,
                status.reconnectCount,
                status.session?.let(BotRuntimeSessionResponse::from),
                lastError,
            )
        }

        private fun error(failure: BotRuntimeFailure, occurredAt: Instant) =
            BotRuntimeErrorResponse(failure.code, failure.message, occurredAt)
    }
}
