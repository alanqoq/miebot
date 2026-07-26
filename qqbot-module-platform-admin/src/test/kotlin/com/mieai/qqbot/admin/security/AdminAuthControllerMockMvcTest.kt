package com.mieai.qqbot.admin.security

import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.web.TraceIdFilter
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [AdminAuthController::class])
@ContextConfiguration(
    classes = [
        AdminAuthController::class,
        ApiExceptionHandler::class,
        AdminSecurityConfiguration::class,
        TraceIdFilter::class,
    ],
)
class AdminAuthControllerMockMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var authenticationService: AdminAuthenticationService

    @Test
    fun statusIssuesAngularXsrfCookie() {
        `when`(authenticationService.setupRequired()).thenReturn(true)

        mockMvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk)
            .andExpect(cookie().exists("XSRF-TOKEN"))
            .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
            .andExpect(jsonPath("$.setupRequired").value(true))
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.username").doesNotExist())
    }

    @Test
    fun setupAndLoginRequireCsrf() {
        mockMvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON).content(setupJson()))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson()))
            .andExpect(status().isForbidden)

        verifyNoInteractions(authenticationService)
    }

    @Test
    fun setupResponseIsAuthenticatedAndNeverLeaksPasswordOrHash() {
        `when`(
            authenticationService.setup(
                eqValue("Admin.User"), eqValue(PASSWORD), anyRequest(), anyResponse(),
            ),
        ).thenReturn("admin.user")

        mockMvc.perform(withXsrf(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON).content(setupJson())))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.setupRequired").value(false))
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.username").value("admin.user"))
            .andExpect(content().string(not(containsString(PASSWORD))))
            .andExpect(content().string(not(containsString("password"))))
            .andExpect(content().string(not(containsString("hash"))))
    }

    @Test
    fun loginResponseIsAuthenticatedAndNeverLeaksPasswordOrHash() {
        `when`(
            authenticationService.login(
                eqValue("Admin.User"), eqValue(PASSWORD), anyRequest(), anyResponse(),
            ),
        ).thenReturn("admin.user")

        mockMvc.perform(withXsrf(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.setupRequired").value(false))
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.username").value("admin.user"))
            .andExpect(content().string(not(containsString(PASSWORD))))
            .andExpect(content().string(not(containsString("password"))))
            .andExpect(content().string(not(containsString("hash"))))
    }

    @Test
    fun fifthFailureMapsTo429WithFifteenMinuteRetryAfter() {
        `when`(
            authenticationService.login(
                eqValue("Admin.User"), eqValue(PASSWORD), anyRequest(), anyResponse(),
            ),
        ).thenThrow(LoginThrottledException(900L))

        mockMvc.perform(withXsrf(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson())))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
            .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"))
            .andExpect(jsonPath("$.message").value("Too many failed sign-in attempts"))
            .andExpect(content().string(not(containsString(PASSWORD))))
            .andExpect(content().string(not(containsString("LoginThrottledException"))))
    }

    @Test
    @WithMockUser(username = "admin.user", roles = ["ADMIN"])
    fun logoutDelegatesAndReturnsAnonymousStatus() {
        `when`(authenticationService.setupRequired()).thenReturn(false)

        mockMvc.perform(withXsrf(post("/api/auth/logout")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.setupRequired").value(false))
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.username").doesNotExist())

        verify(authenticationService).logout(anyRequest(), anyResponse())
    }

    @Test
    @WithMockUser(username = "admin.user", roles = ["ADMIN"])
    fun changesPasswordWithoutReturningCredentials() {
        mockMvc.perform(
            withXsrf(
                post("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content(
                    """{"currentPassword":"old-password-value","newPassword":"new-password-value"}""",
                ),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(content().string(not(containsString("new-password-value"))))

        verify(authenticationService).changePassword(
            eqValue("admin.user"), eqValue("old-password-value"), eqValue("new-password-value"),
        )
    }

    @Test
    fun unauthenticatedBotApiReturns401() {
        mockMvc.perform(get("/api/bots")).andExpect(status().isUnauthorized)
    }

    private fun setupJson() = """
        {
          "username": "Admin.User",
          "password": "$PASSWORD"
        }
    """.trimIndent()

    private fun loginJson() = setupJson()

    private fun anyRequest(): HttpServletRequest =
        any(HttpServletRequest::class.java) ?: MockHttpServletRequest()

    private fun anyResponse(): HttpServletResponse =
        any(HttpServletResponse::class.java) ?: MockHttpServletResponse()

    private fun <T : Any> eqValue(value: T): T = eq(value) ?: value

    private fun withXsrf(request: MockHttpServletRequestBuilder): MockHttpServletRequestBuilder {
        val result = mockMvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk)
            .andExpect(cookie().exists("XSRF-TOKEN"))
            .andReturn()
        val xsrf: Cookie = requireNotNull(result.response.getCookie("XSRF-TOKEN")) { "XSRF-TOKEN cookie" }
        return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.value)
    }

    private companion object {
        const val PASSWORD = "correct-horse-battery-staple"
    }
}
