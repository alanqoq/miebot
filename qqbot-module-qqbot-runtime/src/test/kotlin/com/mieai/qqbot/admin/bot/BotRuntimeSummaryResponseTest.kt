package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.runtime.supervisor.BotRuntimeFailure
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus
import com.mieai.qqbot.runtime.supervisor.BotSessionSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BotRuntimeSummaryResponseTest {
    @Test
    fun `maps one consistent snapshot and only counts online bots as connected`() {
        val online = status(BOT_ID, true, BotRuntimeState.ONLINE, OBSERVED_AT.minusSeconds(60), OBSERVED_AT.minusSeconds(5), OBSERVED_AT.minusSeconds(10), BotSessionSnapshot("gateway-session", 42L), 2L, BotRuntimeFailure("TRANSPORT_FAILURE", "QQ Gateway transport failed", true), OBSERVED_AT.minusSeconds(30))
        val reconnecting = status(BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440001")), true, BotRuntimeState.RECONNECTING, null, null, null, null, 1L, null, null)
        val disabled = status(BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440002")), false, BotRuntimeState.DISABLED, null, null, null, null, 0L, null, null)
        val response = BotRuntimeSummaryResponse.from(listOf(online, reconnecting, disabled), OBSERVED_AT)
        assertThat(response.totalCount).isEqualTo(3)
        assertThat(response.enabledCount).isEqualTo(2)
        assertThat(response.connectedCount).isEqualTo(1)
        assertThat(response.observedAt).isEqualTo(OBSERVED_AT)
        assertThat(response.bots).hasSize(3)
        val mapped = response.bots.first()
        assertThat(mapped.botId).isEqualTo(BOT_ID.value)
        assertThat(mapped.configurationRevision).isEqualTo(3L)
        assertThat(mapped.enabled).isTrue()
        assertThat(mapped.state).isEqualTo("ONLINE")
        assertThat(mapped.connectedAt).isEqualTo(OBSERVED_AT.minusSeconds(60))
        assertThat(mapped.lastHeartbeatAt).isEqualTo(OBSERVED_AT.minusSeconds(5))
        assertThat(mapped.lastDispatchAt).isEqualTo(OBSERVED_AT.minusSeconds(10))
        assertThat(mapped.reconnectCount).isEqualTo(2L)
        assertThat(mapped.session).isEqualTo(BotRuntimeSessionResponse("gateway-session", 42L))
        assertThat(mapped.lastError).isEqualTo(BotRuntimeErrorResponse("TRANSPORT_FAILURE", "QQ Gateway transport failed", OBSERVED_AT.minusSeconds(30)))
    }

    private fun status(botId: BotId, enabled: Boolean, state: BotRuntimeState, readyAt: Instant?, heartbeatAt: Instant?, dispatchAt: Instant?, session: BotSessionSnapshot?, reconnectCount: Long, failure: BotRuntimeFailure?, failureAt: Instant?) =
        BotRuntimeStatus(botId, BotRevision.of(3L), enabled, state, OBSERVED_AT.minusSeconds(90), OBSERVED_AT.minusSeconds(120), readyAt, heartbeatAt, dispatchAt, session, reconnectCount, failure, failureAt)

    private companion object {
        val BOT_ID = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
        val OBSERVED_AT = Instant.parse("2026-07-18T12:00:00Z")
    }
}
