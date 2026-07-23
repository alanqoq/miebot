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

@WebMvcTest(controllers = InboxController.class)
@ContextConfiguration(classes = {
    InboxController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class InboxControllerMockMvcTest {
    private static final UUID EVENT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-18T06:14:58Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InboxAdministrationService service;

    @Test
    void rejectsUnauthenticatedInboxRead() throws Exception {
        mockMvc.perform(get("/api/events/inbox"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void listsInboxWithFiltersAndOpaqueCursorWithoutPayload() throws Exception {
        when(service.list("25", "cursor-1", "message", BOT_ID.toString(), "PRODUCTION", "RECEIVED", "MESSAGE_CREATE"))
                .thenReturn(new InboxPageResponse(
                        List.of(new InboxEventSummaryResponse(
                                EVENT_ID,
                                "PRODUCTION",
                                BOT_ID,
                                "QQ Bot 1",
                                "1905208810",
                                "MESSAGE_CREATE",
                                "platform-1",
                                "RECEIVED",
                                0,
                                RECEIVED_AT,
                                RECEIVED_AT,
                                RECEIVED_AT,
                                null)),
                        "cursor-2",
                        true,
                        RECEIVED_AT));

        mockMvc.perform(get("/api/events/inbox")
                        .param("limit", "25")
                        .param("cursor", "cursor-1")
                        .param("query", "message")
                        .param("botId", BOT_ID.toString())
                        .param("environment", "PRODUCTION")
                        .param("status", "RECEIVED")
                        .param("eventType", "MESSAGE_CREATE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].botDisplayName").value("QQ Bot 1"))
                .andExpect(jsonPath("$.items[0].appId").value("1905208810"))
                .andExpect(jsonPath("$.items[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"))
                .andExpect(jsonPath("$.hasMore").value(true));

        verify(service).list("25", "cursor-1", "message", BOT_ID.toString(), "PRODUCTION", "RECEIVED", "MESSAGE_CREATE");
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void returnsDetailPayloadAsTextAndNeverExposesLeaseInternals() throws Exception {
        String rawPayload = "{\"d\":{\"content\":\"hello\"}}";
        when(service.get(EVENT_ID.toString())).thenReturn(new InboxEventDetailResponse(
                EVENT_ID,
                "PRODUCTION",
                BOT_ID,
                "QQ Bot 1",
                "1905208810",
                "MESSAGE_CREATE",
                "platform-1",
                "RECEIVED",
                0,
                RECEIVED_AT,
                RECEIVED_AT,
                RECEIVED_AT,
                null,
                rawPayload,
                false));

        mockMvc.perform(get("/api/events/inbox/{id}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.payload").value(rawPayload))
                .andExpect(jsonPath("$.payloadTruncated").value(false))
                .andExpect(content().string(not(containsString("leaseOwner"))))
                .andExpect(content().string(not(containsString("fencingToken"))))
                .andExpect(content().string(not(containsString("appSecret"))));

        verify(service).get(EVENT_ID.toString());
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void mapsInvalidQueryToTheStandardBadRequestShape() throws Exception {
        when(service.list(anyString(), eq(null), eq(null), eq(null), eq("NOPE"), eq(null), eq(null)))
                .thenThrow(new IllegalArgumentException("environment is not supported: NOPE"));

        mockMvc.perform(get("/api/events/inbox").param("environment", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("environment is not supported: NOPE"));
    }
}
