package com.mieai.qqbot.admin.bot;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.runtime.configuration.BotConfigurationService;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeFailure;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus;
import com.mieai.qqbot.runtime.supervisor.BotSessionSnapshot;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {BotRuntimeController.class, BotController.class})
@ContextConfiguration(classes = {
    BotRuntimeController.class,
    BotController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class BotRuntimeControllerMockMvcTest {
    private static final BotId ONLINE_BOT = BotId.of(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    private static final BotId RECONNECTING_BOT = BotId.of(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));
    private static final BotId DISABLED_BOT = BotId.of(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440002"));
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotSupervisor supervisor;

    @MockitoBean
    private BotConfigurationService configurationService;

    @Test
    void rejectsUnauthenticatedRuntimeRead() throws Exception {
        mockMvc.perform(get("/api/bots/runtime"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(supervisor);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void returnsAggregateAndSanitizedPerBotRuntimeStatus() throws Exception {
        when(supervisor.statuses()).thenReturn(List.of(
                onlineStatus(),
                runtimeStatus(RECONNECTING_BOT, true, BotRuntimeState.RECONNECTING),
                runtimeStatus(DISABLED_BOT, false, BotRuntimeState.DISABLED)));

        mockMvc.perform(get("/api/bots/runtime"))
                .andExpect(status().isOk())
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
                .andExpect(jsonPath("$.bots[0].lastError.message")
                        .value("QQ Gateway transport failed"))
                .andExpect(jsonPath("$.bots[0].lastError.occurredAt")
                        .value("2026-07-18T11:59:30Z"))
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
                .andExpect(content().string(not(containsString("cause"))));
    }

    private static BotRuntimeStatus onlineStatus() {
        return new BotRuntimeStatus(
                ONLINE_BOT,
                BotRevision.of(3L),
                true,
                BotRuntimeState.ONLINE,
                NOW.minusSeconds(90),
                Optional.of(NOW.minusSeconds(120)),
                Optional.of(NOW.minusSeconds(60)),
                Optional.of(NOW.minusSeconds(5)),
                Optional.of(NOW.minusSeconds(10)),
                Optional.of(new BotSessionSnapshot("gateway-session", 42L)),
                2L,
                Optional.of(new BotRuntimeFailure(
                        "TRANSPORT_FAILURE", "QQ Gateway transport failed", true)),
                Optional.of(NOW.minusSeconds(30)));
    }

    private static BotRuntimeStatus runtimeStatus(
            BotId botId, boolean enabled, BotRuntimeState state) {
        return new BotRuntimeStatus(
                botId,
                BotRevision.of(3L),
                enabled,
                state,
                NOW.minusSeconds(90),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                state == BotRuntimeState.RECONNECTING ? 1L : 0L,
                Optional.empty(),
                Optional.empty());
    }
}
