package com.mieai.qqbot.admin.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = AdminAuthController.class)
@ContextConfiguration(classes = {
    AdminAuthController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class AdminAuthControllerMockMvcTest {
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAuthenticationService authenticationService;

    @Test
    void statusIssuesAngularXsrfCookie() throws Exception {
        when(authenticationService.setupRequired()).thenReturn(true);

        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    void setupAndLoginRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setupJson()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authenticationService);
    }

    @Test
    void setupResponseIsAuthenticatedAndNeverLeaksPasswordOrHash() throws Exception {
        when(authenticationService.setup(
                        eq("Admin.User"), eq(PASSWORD),
                        any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn("admin.user");

        mockMvc.perform(withXsrf(post("/api/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setupJson())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin.user"))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash"))));
    }

    @Test
    void loginResponseIsAuthenticatedAndNeverLeaksPasswordOrHash() throws Exception {
        when(authenticationService.login(
                        eq("Admin.User"), eq(PASSWORD),
                        any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn("admin.user");

        mockMvc.perform(withXsrf(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin.user"))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("hash"))));
    }

    @Test
    void fifthFailureMapsTo429WithFifteenMinuteRetryAfter() throws Exception {
        when(authenticationService.login(
                        eq("Admin.User"), eq(PASSWORD),
                        any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenThrow(new LoginThrottledException(900L));

        mockMvc.perform(withXsrf(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"))
                .andExpect(jsonPath("$.message").value("Too many failed sign-in attempts"))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("LoginThrottledException"))));
    }

    @Test
    @WithMockUser(username = "admin.user", roles = "ADMIN")
    void logoutDelegatesAndReturnsAnonymousStatus() throws Exception {
        when(authenticationService.setupRequired()).thenReturn(false);

        mockMvc.perform(withXsrf(post("/api/auth/logout")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").doesNotExist());

        verify(authenticationService).logout(
                any(HttpServletRequest.class), any(HttpServletResponse.class));
    }

    @Test
    @WithMockUser(username = "admin.user", roles = "ADMIN")
    void changesPasswordWithoutReturningCredentials() throws Exception {
        mockMvc.perform(withXsrf(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-password-value","newPassword":"new-password-value"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(content().string(not(containsString("new-password-value"))));

        verify(authenticationService).changePassword(
                eq("admin.user"), eq("old-password-value"), eq("new-password-value"));
    }

    @Test
    void unauthenticatedBotApiReturns401() throws Exception {
        mockMvc.perform(get("/api/bots"))
                .andExpect(status().isUnauthorized());
    }

    private static String setupJson() {
        return """
                {
                  "username": "Admin.User",
                  "password": "%s"
                }
                """.formatted(PASSWORD);
    }

    private static String loginJson() {
        return setupJson();
    }

    private MockHttpServletRequestBuilder withXsrf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult status = mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie xsrf = Objects.requireNonNull(
                status.getResponse().getCookie("XSRF-TOKEN"), "XSRF-TOKEN cookie");
        return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }
}
