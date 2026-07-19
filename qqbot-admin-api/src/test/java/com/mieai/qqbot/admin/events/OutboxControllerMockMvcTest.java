package com.mieai.qqbot.admin.events;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {OutboxController.class, DeadLetterController.class})
@ContextConfiguration(classes = {
    OutboxController.class,
    DeadLetterController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class OutboxControllerMockMvcTest {
    private static final UUID JOB_ID = UUID.fromString("41000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final Instant CREATED_AT = Instant.parse("2026-07-18T06:14:58Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxAdministrationService service;

    @Test
    void rejectsUnauthenticatedOutboxRead() throws Exception {
        mockMvc.perform(get("/api/events/outbox"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void listsOutboxWithStatsAndNeverIncludesPayload() throws Exception {
        when(service.list("25", "cursor-1", "reply", BOT_ID.toString(),
                "PRODUCTION", "RETRY_WAIT", "SEND_MESSAGE"))
                .thenReturn(new OutboxPageResponse(
                        List.of(new OutboxJobSummaryResponse(
                                JOB_ID,
                                "PRODUCTION",
                                BOT_ID,
                                "QQ Bot 1",
                                "1905208810",
                                null,
                                "SEND_MESSAGE",
                                "RETRY_WAIT",
                                2,
                                CREATED_AT,
                                CREATED_AT,
                                CREATED_AT,
                                null,
                                "timeout")),
                        "cursor-2",
                        true,
                        CREATED_AT,
                        new OutboxQueueStatsResponse(3, 1, 0, 1, 1, 0, 0)));

        mockMvc.perform(get("/api/outbox")
                        .param("limit", "25")
                        .param("cursor", "cursor-1")
                        .param("query", "reply")
                        .param("botId", BOT_ID.toString())
                        .param("environment", "PRODUCTION")
                        .param("status", "RETRY_WAIT")
                        .param("jobType", "SEND_MESSAGE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("RETRY_WAIT"))
                .andExpect(jsonPath("$.stats.pendingCount").value(1))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.items[0].dedupKey").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"));

        verify(service).list("25", "cursor-1", "reply", BOT_ID.toString(),
                "PRODUCTION", "RETRY_WAIT", "SEND_MESSAGE");
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void deadLetterViewForcesDeadLetterFilterAndHidesLeaseInternals() throws Exception {
        String payload = "{\"content\":\"failed\"}";
        when(service.list("50", null, null, null, null, null, null, true))
                .thenReturn(new OutboxPageResponse(
                        List.of(), null, false, CREATED_AT,
                        new OutboxQueueStatsResponse(0, 0, 0, 0, 0, 0, 0)));
        when(service.get(JOB_ID.toString(), true)).thenReturn(new OutboxJobDetailResponse(
                JOB_ID,
                "PRODUCTION",
                BOT_ID,
                "QQ Bot 1",
                "1905208810",
                null,
                "SEND_MESSAGE",
                "reply:dead",
                "DEAD_LETTER",
                3,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                "retry policy exhausted",
                payload,
                false));

        mockMvc.perform(get("/api/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/events/dlq/{id}", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEAD_LETTER"))
                .andExpect(jsonPath("$.payload").value(payload))
                .andExpect(content().string(not(containsString("leaseOwner"))))
                .andExpect(content().string(not(containsString("fencingToken"))));

        verify(service).list("50", null, null, null, null, null, null, true);
        verify(service).get(JOB_ID.toString(), true);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void mapsInvalidQueryToTheStandardBadRequestShape() throws Exception {
        when(service.list(anyString(), eq(null), eq(null), eq(null), eq(null), eq("NOPE"), eq(null)))
                .thenThrow(new IllegalArgumentException("status is not supported: NOPE"));

        mockMvc.perform(get("/api/events/outbox").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("status is not supported: NOPE"));
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void dlqRejectsAnyStatusOtherThanDeadLetterWithoutParsingItAsAQueueStatus() throws Exception {
        when(service.list("50", null, null, null, null, "NOPE", null, true))
                .thenThrow(new IllegalArgumentException("status must be DEAD_LETTER for the DLQ view"));

        mockMvc.perform(get("/api/events/dlq").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("status must be DEAD_LETTER for the DLQ view"));
    }
}
