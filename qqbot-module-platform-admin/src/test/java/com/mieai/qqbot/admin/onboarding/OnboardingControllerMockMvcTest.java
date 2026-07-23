package com.mieai.qqbot.admin.onboarding;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OnboardingController.class)
@ContextConfiguration(classes = {
    OnboardingController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class OnboardingControllerMockMvcTest {
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-17T12:34:56Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnboardingAdministrationService service;

    @Test
    void statusRequiresAdministratorAuthentication() throws Exception {
        mockMvc.perform(get("/api/system/onboarding"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void returnsAllStatusFieldsIncludingNullValues() throws Exception {
        when(service.status()).thenReturn(new OnboardingStatusResponse(
                OnboardingStage.DATABASE, null, 0L, null));

        mockMvc.perform(get("/api/system/onboarding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("DATABASE"))
                .andExpect(jsonPath("$.databaseType").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.botCount").value(0))
                .andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void confirmsOnlyTheExpectedActiveDatabase() throws Exception {
        when(service.databaseConfigured(4L, DatabaseType.POSTGRESQL))
                .thenReturn(new OnboardingStatusResponse(
                        OnboardingStage.BOT, DatabaseType.POSTGRESQL, 0L, null));

        mockMvc.perform(post("/api/system/onboarding/database-configured")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":4,"databaseType":"POSTGRESQL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("BOT"))
                .andExpect(jsonPath("$.databaseType").value("POSTGRESQL"));

        verify(service).databaseConfigured(4L, DatabaseType.POSTGRESQL);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void validatesDatabaseConfirmationAndRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/system/onboarding/database-configured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4,\"databaseType\":\"SQLITE\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/system/onboarding/database-configured")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void completesWithAnEmptyJsonBodyAndMapsReadinessConflict() throws Exception {
        when(service.complete()).thenReturn(new OnboardingStatusResponse(
                OnboardingStage.COMPLETE, DatabaseType.SQLITE, 1L, COMPLETED_AT));

        mockMvc.perform(post("/api/system/onboarding/complete")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("COMPLETE"))
                .andExpect(jsonPath("$.botCount").value(1))
                .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()));

        when(service.complete()).thenThrow(new OnboardingOperationException(
                org.springframework.http.HttpStatus.CONFLICT,
                "ONBOARDING_BOT_REQUIRED",
                "At least one QQ bot is required"));
        mockMvc.perform(post("/api/system/onboarding/complete").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_BOT_REQUIRED"));
    }
}
