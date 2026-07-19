package com.mieai.qqbot.admin.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DatabaseConfigurationController.class)
@ContextConfiguration(classes = {
    DatabaseConfigurationController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class DatabaseConfigurationControllerMockMvcTest {
    private static final String PASSWORD = "database-password-secret";
    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatabaseAdministrationService service;

    @Test
    void protectsDatabaseConfigurationFromAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/system/database/configuration"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void returnsOnlySanitizedCurrentConfiguration() throws Exception {
        when(service.current()).thenReturn(mysqlConfiguration(4L));

        mockMvc.perform(get("/api/system/database/configuration"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.type").value("MYSQL"))
                .andExpect(jsonPath("$.host").value("db.internal"))
                .andExpect(jsonPath("$.databaseName").value("qqbot"))
                .andExpect(jsonPath("$.username").value("qqbot_app"))
                .andExpect(jsonPath("$.passwordConfigured").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void validatesFieldsForTheSelectedDatabaseType() throws Exception {
        mockMvc.perform(post("/api/system/database/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "POSTGRESQL",
                                  "port": 5432,
                                  "databaseName": "",
                                  "username": "app",
                                  "sslMode": "PREFERRED",
                                  "connectTimeoutMs": 5000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.host").exists())
                .andExpect(jsonPath("$.fieldErrors.databaseName").exists());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void testsCredentialsWithoutReturningOrRetainingThePassword() throws Exception {
        when(service.test(any(DatabaseSettings.class))).thenAnswer(invocation -> {
            DatabaseSettings settings = invocation.getArgument(0);
            char[] value = settings.password().copyValue();
            try {
                assertThat(value).containsExactly(PASSWORD.toCharArray());
                assertThat(settings.toString()).doesNotContain(PASSWORD);
            } finally {
                Arrays.fill(value, '\0');
            }
            return successfulTest(DatabaseType.POSTGRESQL);
        });

        mockMvc.perform(post("/api/system/database/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postgresCandidateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.writeVerified").value(true))
                .andExpect(jsonPath("$.schemaState").value("READY"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void requiresCsrfForSwitchingDatabases() throws Exception {
        mockMvc.perform(post("/api/system/database/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(switchJson()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void switchesUsingRevisionAndAuthenticatedAdministrator() throws Exception {
        when(service.switchDatabase(eq(4L), any(DatabaseSettings.class), eq("admin")))
                .thenReturn(new DatabaseSwitchResult(mysqlConfiguration(5L), successfulTest(DatabaseType.MYSQL)));

        mockMvc.perform(post("/api/system/database/switch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(switchJson()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.configuration.revision").value(5))
                .andExpect(jsonPath("$.configuration.type").value("MYSQL"))
                .andExpect(jsonPath("$.verification.success").value(true))
                .andExpect(jsonPath("$.configuration.password").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));

        verify(service).switchDatabase(eq(4L), any(DatabaseSettings.class), eq("admin"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void reloadsTheMountedConfigurationWithOptimisticLocking() throws Exception {
        when(service.reload(4L, "admin"))
                .thenReturn(new DatabaseSwitchResult(mysqlConfiguration(5L), successfulTest(DatabaseType.MYSQL)));

        mockMvc.perform(post("/api/system/database/reload")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":4}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.configuration.type").value("MYSQL"));

        verify(service).reload(4L, "admin");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void mapsDatabaseFailuresToSanitizedApiErrors() throws Exception {
        when(service.test(any(DatabaseSettings.class))).thenThrow(new DatabaseAdministrationException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DATABASE_CONNECTION_FAILED",
                "The database connection could not be established"));

        mockMvc.perform(post("/api/system/database/test")
                        .with(csrf())
                        .header(TraceIdFilter.HEADER_NAME, "database-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postgresCandidateJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DATABASE_CONNECTION_FAILED"))
                .andExpect(jsonPath("$.traceId").value("database-trace"))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("SQLException"))));
    }

    private static DatabaseConfigurationView mysqlConfiguration(long revision) {
        return new DatabaseConfigurationView(
                revision,
                DatabaseType.MYSQL,
                null,
                null,
                "db.internal",
                3306,
                "qqbot",
                "qqbot_app",
                DatabaseSslMode.PREFERRED,
                5000L,
                true,
                "MySQL",
                "8.4",
                false,
                NOW);
    }

    private static DatabaseTestResult successfulTest(DatabaseType type) {
        return new DatabaseTestResult(
                true,
                type,
                type == DatabaseType.POSTGRESQL ? "PostgreSQL" : "MySQL",
                type == DatabaseType.POSTGRESQL ? "15.8" : "8.4",
                17L,
                true,
                true,
                DatabaseSchemaState.READY,
                "3",
                false,
                false,
                2L,
                NOW);
    }

    private static String postgresCandidateJson() {
        return """
                {
                  "type": "POSTGRESQL",
                  "host": "postgres.internal",
                  "port": 5432,
                  "databaseName": "qqbot",
                  "username": "qqbot_app",
                  "password": "%s",
                  "sslMode": "PREFERRED",
                  "connectTimeoutMs": 5000
                }
                """.formatted(PASSWORD);
    }

    private static String switchJson() {
        return """
                {
                  "expectedRevision": 4,
                  "candidate": {
                    "type": "MYSQL",
                    "host": "db.internal",
                    "port": 3306,
                    "databaseName": "qqbot",
                    "username": "qqbot_app",
                    "password": "%s",
                    "sslMode": "PREFERRED",
                    "connectTimeoutMs": 5000
                  }
                }
                """.formatted(PASSWORD);
    }
}
