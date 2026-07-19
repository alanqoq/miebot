package com.mieai.qqbot.admin.events;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(controllers = {PluginDeliveryController.class, PluginDeadLetterController.class})
@ContextConfiguration(classes = {
        PluginDeliveryController.class, PluginDeadLetterController.class,
        ApiExceptionHandler.class, AdminSecurityConfiguration.class, TraceIdFilter.class
})
class PluginDeliveryControllerMockMvcTest {
    private static final UUID DELIVERY = UUID.fromString("880e8400-e29b-41d4-a716-446655440001");

    @Autowired MockMvc mockMvc;
    @MockitoBean PluginDeliveryAdministrationService service;

    @Test
    void rejectsUnauthenticatedPluginDeliveryRead() throws Exception {
        mockMvc.perform(get("/api/events/plugin-deliveries"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void exposesNoStoreDeliveryAndPluginDlqViews() throws Exception {
        PluginDeliveryQueueStatsResponse stats = new PluginDeliveryQueueStatsResponse(1, 0, 0, 0, 0, 1, 0);
        PluginDeliverySummaryResponse item = new PluginDeliverySummaryResponse(
                DELIVERY, UUID.randomUUID(), UUID.randomUUID(), "echo", UUID.randomUUID(),
                "Support Bot", "default", "DEAD_LETTER", 5, Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), "IllegalStateException: failed");
        PluginDeliveryPageResponse page = new PluginDeliveryPageResponse(
                List.of(item), null, false, Instant.now(), stats);
        when(service.list("50", null, null, null, null, false)).thenReturn(page);
        when(service.list("50", null, null, null, null, true)).thenReturn(page);

        mockMvc.perform(get("/api/events/plugin-deliveries"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].status").value("DEAD_LETTER"));
        mockMvc.perform(get("/api/events/plugin-dlq"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stats.deadLetterCount").value(1));
        verify(service).list("50", null, null, null, null, false);
        verify(service).list("50", null, null, null, null, true);
    }
}
