package com.mieai.qqbot.admin.events

import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration
import com.mieai.qqbot.admin.web.TraceIdFilter
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.verify
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [OutboxController::class, DeadLetterController::class])
@ContextConfiguration(
    classes = [
        OutboxController::class,
        DeadLetterController::class,
        ApiExceptionHandler::class,
        AdminSecurityConfiguration::class,
        TraceIdFilter::class,
    ],
)
class OutboxControllerMockMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: OutboxAdministrationService

    @Test
    fun `rejects unauthenticated outbox read`() {
        mockMvc.perform(get("/api/events/outbox"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `lists outbox with statistics without payload`() {
        `when`(
            service.list(
                "25",
                "cursor-1",
                "reply",
                BOT_ID.toString(),
                "PRODUCTION",
                "RETRY_WAIT",
                "SEND_MESSAGE",
            ),
        ).thenReturn(
            OutboxPageResponse(
                listOf(
                    OutboxJobSummaryResponse(
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
                        "timeout",
                    ),
                ),
                "cursor-2",
                true,
                CREATED_AT,
                OutboxQueueStatsResponse(3, 1, 0, 1, 1, 0, 0),
            ),
        )

        mockMvc.perform(
            get("/api/outbox")
                .param("limit", "25")
                .param("cursor", "cursor-1")
                .param("query", "reply")
                .param("botId", BOT_ID.toString())
                .param("environment", "PRODUCTION")
                .param("status", "RETRY_WAIT")
                .param("jobType", "SEND_MESSAGE"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].status").value("RETRY_WAIT"))
            .andExpect(jsonPath("$.stats.pendingCount").value(1))
            .andExpect(jsonPath("$.items[0].payload").doesNotExist())
            .andExpect(jsonPath("$.items[0].dedupKey").doesNotExist())
            .andExpect(jsonPath("$.nextCursor").value("cursor-2"))

        verify(service).list(
            "25",
            "cursor-1",
            "reply",
            BOT_ID.toString(),
            "PRODUCTION",
            "RETRY_WAIT",
            "SEND_MESSAGE",
        )
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `exposes persistent delivery receipt in outbox detail`() {
        `when`(service.get(JOB_ID.toString())).thenReturn(
            OutboxJobDetailResponse(
                JOB_ID,
                "PRODUCTION",
                BOT_ID,
                "QQ Bot 1",
                "1905208810",
                null,
                "SEND_MESSAGE",
                "reply:success",
                "SUCCEEDED",
                1,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                null,
                BINDING_ID,
                "bot-message-900",
                3,
                "2026-07-24T12:00:00Z",
                "{\"content\":\"sent\"}",
                false,
            ),
        )

        mockMvc.perform(get("/api/events/outbox/{id}", JOB_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.producerBindingId").value(BINDING_ID.toString()))
            .andExpect(jsonPath("$.platformMessageId").value("bot-message-900"))
            .andExpect(jsonPath("$.platformMessageSequence").value(3))
            .andExpect(jsonPath("$.platformTimestamp").value("2026-07-24T12:00:00Z"))

        verify(service).get(JOB_ID.toString())
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `dead letter view forces filter and hides lease internals`() {
        val payload = "{\"content\":\"failed\"}"
        `when`(service.list("50", null, null, null, null, null, null, true)).thenReturn(
            OutboxPageResponse(
                emptyList(),
                null,
                false,
                CREATED_AT,
                OutboxQueueStatsResponse(0, 0, 0, 0, 0, 0, 0),
            ),
        )
        `when`(service.get(JOB_ID.toString(), true)).thenReturn(
            OutboxJobDetailResponse(
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
                null,
                null,
                null,
                null,
                payload,
                false,
            ),
        )

        mockMvc.perform(get("/api/dead-letters"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
        mockMvc.perform(get("/api/events/dlq/{id}", JOB_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DEAD_LETTER"))
            .andExpect(jsonPath("$.payload").value(payload))
            .andExpect(content().string(not(containsString("leaseOwner"))))
            .andExpect(content().string(not(containsString("fencingToken"))))

        verify(service).list("50", null, null, null, null, null, null, true)
        verify(service).get(JOB_ID.toString(), true)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `maps invalid query to the standard bad request shape`() {
        `when`(
            service.list(anyString(), isNull(), isNull(), isNull(), isNull(), eq("NOPE"), isNull()),
        ).thenThrow(IllegalArgumentException("status is not supported: NOPE"))

        mockMvc.perform(get("/api/events/outbox").param("status", "NOPE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("status is not supported: NOPE"))
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `DLQ rejects any status other than dead letter before queue parsing`() {
        `when`(service.list("50", null, null, null, null, "NOPE", null, true))
            .thenThrow(IllegalArgumentException("status must be DEAD_LETTER for the DLQ view"))

        mockMvc.perform(get("/api/events/dlq").param("status", "NOPE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("status must be DEAD_LETTER for the DLQ view"))
    }

    companion object {
        private val JOB_ID: UUID = UUID.fromString("41000000-0000-0000-0000-000000000001")
        private val BOT_ID: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        private val BINDING_ID: UUID = UUID.fromString("65000000-0000-0000-0000-000000000001")
        private val CREATED_AT: Instant = Instant.parse("2026-07-18T06:14:58Z")
    }
}
