package com.mieai.qqbot.admin.onboarding

import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration
import com.mieai.qqbot.admin.web.TraceIdFilter
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(controllers = [OnboardingController::class])
@ContextConfiguration(
    classes = [
        OnboardingController::class,
        ApiExceptionHandler::class,
        AdminSecurityConfiguration::class,
        TraceIdFilter::class,
    ],
)
class OnboardingControllerMockMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: OnboardingAdministrationService

    @Test
    fun `status requires administrator authentication`() {
        mockMvc.perform(get("/api/system/onboarding"))
            .andExpect(status().isUnauthorized)

        verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `returns all status fields including null values`() {
        `when`(service.status()).thenReturn(
            OnboardingStatusResponse(OnboardingStage.DATABASE, null, 0, null),
        )

        mockMvc.perform(get("/api/system/onboarding"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stage").value("DATABASE"))
            .andExpect(jsonPath("$.databaseType").value(nullValue()))
            .andExpect(jsonPath("$.botCount").value(0))
            .andExpect(jsonPath("$.completedAt").value(nullValue()))
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `confirms only the expected active database`() {
        `when`(service.databaseConfigured(4, DatabaseType.POSTGRESQL)).thenReturn(
            OnboardingStatusResponse(OnboardingStage.BOT, DatabaseType.POSTGRESQL, 0, null),
        )

        mockMvc.perform(
            post("/api/system/onboarding/database-configured")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedRevision":4,"databaseType":"POSTGRESQL"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stage").value("BOT"))
            .andExpect(jsonPath("$.databaseType").value("POSTGRESQL"))

        verify(service).databaseConfigured(4, DatabaseType.POSTGRESQL)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `validates database confirmation and requires CSRF`() {
        mockMvc.perform(
            post("/api/system/onboarding/database-configured")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedRevision":4,"databaseType":"SQLITE"}"""),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/system/onboarding/database-configured")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedRevision":0}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `completes with an empty JSON body and maps readiness conflict`() {
        `when`(service.complete()).thenReturn(
            OnboardingStatusResponse(
                OnboardingStage.COMPLETE,
                DatabaseType.SQLITE,
                1,
                COMPLETED_AT,
            ),
        )

        mockMvc.perform(
            post("/api/system/onboarding/complete")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stage").value("COMPLETE"))
            .andExpect(jsonPath("$.botCount").value(1))
            .andExpect(jsonPath("$.completedAt").value(COMPLETED_AT.toString()))

        `when`(service.complete()).thenThrow(
            OnboardingOperationException(
                HttpStatus.CONFLICT,
                "ONBOARDING_BOT_REQUIRED",
                "At least one QQ bot is required",
            ),
        )
        mockMvc.perform(post("/api/system/onboarding/complete").with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ONBOARDING_BOT_REQUIRED"))
    }

    companion object {
        private val COMPLETED_AT: Instant = Instant.parse("2026-07-17T12:34:56Z")
    }
}
