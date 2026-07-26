package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration
import com.mieai.qqbot.admin.web.TraceIdFilter
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.runtime.configuration.BotConfigurationService
import com.mieai.qqbot.runtime.supervisor.BotRuntimeFailure
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus
import com.mieai.qqbot.runtime.supervisor.BotSessionSnapshot
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [BotRuntimeController::class, BotController::class])
@ContextConfiguration(classes = [BotRuntimeController::class, BotController::class, ApiExceptionHandler::class, AdminSecurityConfiguration::class, TraceIdFilter::class])
class BotRuntimeControllerMockMvcTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var supervisor: BotSupervisor
    @MockitoBean lateinit var configurationService: BotConfigurationService

    @Test fun `rejects unauthenticated runtime read`() {
        mockMvc.perform(get("/api/bots/runtime")).andExpect(status().isUnauthorized)
        verifyNoInteractions(supervisor)
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `returns aggregate and sanitized per bot runtime status`() {
        `when`(supervisor.statuses()).thenReturn(listOf(onlineStatus(), runtimeStatus(RECONNECTING_BOT, true, BotRuntimeState.RECONNECTING), runtimeStatus(DISABLED_BOT, false, BotRuntimeState.DISABLED)))
        mockMvc.perform(get("/api/bots/runtime"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.enabledCount").value(2))
            .andExpect(jsonPath("$.connectedCount").value(1))
            .andExpect(jsonPath("$.observedAt").exists())
            .andExpect(jsonPath("$.bots.length()").value(3))
            .andExpect(jsonPath("$.bots[0].botId").value(ONLINE_BOT.toString()))
            .andExpect(jsonPath("$.bots[0].configurationRevision").value(3))
            .andExpect(jsonPath("$.bots[0].enabled").value(true))
            .andExpect(jsonPath("$.bots[0].state").value("ONLINE"))
            .andExpect(jsonPath("$.bots[0].stateChangedAt").value("2026-07-18T11:58:30Z"))
            .andExpect(jsonPath("$.bots[0].connectedAt").value("2026-07-18T11:59:00Z"))
            .andExpect(jsonPath("$.bots[0].lastHeartbeatAt").value("2026-07-18T11:59:55Z"))
            .andExpect(jsonPath("$.bots[0].lastDispatchAt").value("2026-07-18T11:59:50Z"))
            .andExpect(jsonPath("$.bots[0].reconnectCount").value(2))
            .andExpect(jsonPath("$.bots[0].session.id").value("gateway-session"))
            .andExpect(jsonPath("$.bots[0].session.sequence").value(42))
            .andExpect(jsonPath("$.bots[0].lastError.code").value("TRANSPORT_FAILURE"))
            .andExpect(jsonPath("$.bots[0].lastError.message").value("QQ Gateway transport failed"))
            .andExpect(jsonPath("$.bots[0].lastError.occurredAt").value("2026-07-18T11:59:30Z"))
            .andExpect(jsonPath("$.bots[0].lastError.retryable").doesNotExist())
            .andExpect(jsonPath("$.bots[0].desiredEnabled").doesNotExist())
            .andExpect(jsonPath("$.bots[0].readyAt").doesNotExist())
            .andExpect(jsonPath("$.bots[0].lastFailure").doesNotExist())
            .andExpect(jsonPath("$.bots[1].connectedAt").value(nullValue()))
            .andExpect(jsonPath("$.bots[1].session").value(nullValue()))
            .andExpect(jsonPath("$.bots[1].lastError").value(nullValue()))
            .andExpect(content().string(not(containsString("appSecret"))))
            .andExpect(content().string(not(containsString("accessToken"))))
            .andExpect(content().string(not(containsString("stackTrace"))))
            .andExpect(content().string(not(containsString("cause"))))
    }

    private fun onlineStatus() = BotRuntimeStatus(ONLINE_BOT, BotRevision.of(3), true, BotRuntimeState.ONLINE, NOW.minusSeconds(90), NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(5), NOW.minusSeconds(10), BotSessionSnapshot("gateway-session", 42), 2, BotRuntimeFailure("TRANSPORT_FAILURE", "QQ Gateway transport failed", true), NOW.minusSeconds(30))
    private fun runtimeStatus(botId: BotId, enabled: Boolean, state: BotRuntimeState) = BotRuntimeStatus(botId, BotRevision.of(3), enabled, state, NOW.minusSeconds(90), null, null, null, null, null, if (state == BotRuntimeState.RECONNECTING) 1 else 0, null, null)
    private companion object { val ONLINE_BOT = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")); val RECONNECTING_BOT = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440001")); val DISABLED_BOT = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440002")); val NOW = Instant.parse("2026-07-18T12:00:00Z") }
}
