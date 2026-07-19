package com.mieai.qqbot.admin.plugins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PluginBindingController.class)
@ContextConfiguration(classes = {
        PluginBindingController.class, ApiExceptionHandler.class,
        AdminSecurityConfiguration.class, TraceIdFilter.class
})
class PluginBindingControllerMockMvcTest {
    private static final UUID BINDING = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
    private static final UUID BOT = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @Autowired MockMvc mockMvc;
    @MockitoBean PluginBindingAdministrationService service;

    @Test
    void rejectsUnauthenticatedBindingRead() throws Exception {
        mockMvc.perform(get("/api/plugin-bindings"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void supportsBindingCrudAndUsesNoSecretFields() throws Exception {
        PluginBindingResponse response = new PluginBindingResponse(BINDING, "echo", BOT,
                "{}", true, 0, Instant.parse("2026-07-19T00:00:00Z"),
                Instant.parse("2026-07-19T00:00:00Z"));
        when(service.list(null, null)).thenReturn(List.of(response));
        when(service.create(any(CreatePluginBindingRequest.class))).thenReturn(response);
        when(service.update(any(UUID.class), any(UpdatePluginBindingRequest.class))).thenReturn(response);

        mockMvc.perform(get("/api/plugin-bindings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].pluginId").value("echo"));
        mockMvc.perform(post("/api/plugin-bindings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"pluginId\":\"echo\",\"botId\":\"" + BOT +
                                "\",\"configJson\":\"{}\",\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/plugin-bindings/" + BINDING));
        mockMvc.perform(put("/api/plugin-bindings/{id}", BINDING).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0,\"configJson\":\"{}\",\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/plugin-bindings/{id}", BINDING).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).delete(BINDING);
    }
}
