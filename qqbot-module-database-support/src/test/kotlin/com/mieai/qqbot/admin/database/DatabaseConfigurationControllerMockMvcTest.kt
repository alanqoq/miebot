package com.mieai.qqbot.admin.database

import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration
import com.mieai.qqbot.admin.web.TraceIdFilter
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Arrays

@WebMvcTest(controllers = [DatabaseConfigurationController::class])
@ContextConfiguration(classes = [DatabaseConfigurationController::class, ApiExceptionHandler::class, AdminSecurityConfiguration::class, TraceIdFilter::class])
class DatabaseConfigurationControllerMockMvcTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var service: DatabaseAdministrationService

    @Test fun protectsDatabaseConfigurationFromAnonymousRequests() {
        mockMvc.perform(get("/api/system/database/configuration")).andExpect(status().isUnauthorized)
        verifyNoInteractions(service)
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun returnsOnlySanitizedCurrentConfiguration() {
        `when`(service.current()).thenReturn(mysqlConfiguration(4))
        mockMvc.perform(get("/api/system/database/configuration")).andExpect(status().isOk).andExpect(header().string("ETag", "\"4\"")).andExpect(jsonPath("$.type").value("MYSQL")).andExpect(jsonPath("$.host").value("db.internal")).andExpect(jsonPath("$.databaseName").value("qqbot")).andExpect(jsonPath("$.username").value("qqbot_app")).andExpect(jsonPath("$.passwordConfigured").value(true)).andExpect(jsonPath("$.password").doesNotExist()).andExpect(content().string(not(containsString(PASSWORD))))
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun validatesFieldsForTheSelectedDatabaseType() {
        mockMvc.perform(post("/api/system/database/test").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"type":"POSTGRESQL","port":5432,"databaseName":"","username":"app","sslMode":"PREFERRED","connectTimeoutMs":5000}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.fieldErrors.host").exists()).andExpect(jsonPath("$.fieldErrors.databaseName").exists())
        verifyNoInteractions(service)
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun testsCredentialsWithoutReturningOrRetainingThePassword() {
        `when`(service.test(anySettings())).thenAnswer { invocation ->
            val settings = invocation.getArgument<DatabaseSettings>(0); val value = requireNotNull(settings.password).copyValue()
            try { assertThat(value).containsExactly(*PASSWORD.toCharArray()); assertThat(settings.toString()).doesNotContain(PASSWORD) } finally { Arrays.fill(value, '\u0000') }
            successfulTest(DatabaseType.POSTGRESQL)
        }
        mockMvc.perform(post("/api/system/database/test").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(postgresCandidateJson())).andExpect(status().isOk).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.writeVerified").value(true)).andExpect(jsonPath("$.schemaState").value("READY")).andExpect(jsonPath("$.password").doesNotExist()).andExpect(content().string(not(containsString(PASSWORD))))
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun requiresCsrfForSwitchingDatabases() { mockMvc.perform(post("/api/system/database/switch").contentType(MediaType.APPLICATION_JSON).content(switchJson())).andExpect(status().isForbidden); verifyNoInteractions(service) }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun validatesMissingSwitchCandidate() { mockMvc.perform(post("/api/system/database/switch").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":4}")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.fieldErrors.candidate").exists()); verifyNoInteractions(service) }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun switchesUsingRevisionAndAuthenticatedAdministrator() {
        `when`(service.switchDatabase(eq(4L), anySettings(), eqValue("admin"))).thenReturn(DatabaseSwitchResult(mysqlConfiguration(5), successfulTest(DatabaseType.MYSQL)))
        mockMvc.perform(post("/api/system/database/switch").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(switchJson())).andExpect(status().isOk).andExpect(header().string("ETag", "\"5\"")).andExpect(jsonPath("$.configuration.revision").value(5)).andExpect(jsonPath("$.configuration.type").value("MYSQL")).andExpect(jsonPath("$.verification.success").value(true)).andExpect(jsonPath("$.configuration.password").doesNotExist()).andExpect(content().string(not(containsString(PASSWORD))))
        verify(service).switchDatabase(eq(4L), anySettings(), eqValue("admin"))
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun reloadsTheMountedConfigurationWithOptimisticLocking() {
        `when`(service.reload(4, "admin")).thenReturn(DatabaseSwitchResult(mysqlConfiguration(5), successfulTest(DatabaseType.MYSQL)))
        mockMvc.perform(post("/api/system/database/reload").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":4}")).andExpect(status().isOk).andExpect(header().string("ETag", "\"5\"")).andExpect(jsonPath("$.configuration.type").value("MYSQL"))
        verify(service).reload(4, "admin")
    }

    @Test @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun mapsDatabaseFailuresToSanitizedApiErrors() {
        `when`(service.test(anySettings())).thenThrow(DatabaseAdministrationException(HttpStatus.UNPROCESSABLE_ENTITY, "DATABASE_CONNECTION_FAILED", "The database connection could not be established"))
        mockMvc.perform(post("/api/system/database/test").with(csrf()).header(TraceIdFilter.HEADER_NAME, "database-trace").contentType(MediaType.APPLICATION_JSON).content(postgresCandidateJson())).andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("DATABASE_CONNECTION_FAILED")).andExpect(jsonPath("$.traceId").value("database-trace")).andExpect(content().string(not(containsString(PASSWORD)))).andExpect(content().string(not(containsString("SQLException"))))
    }

    private fun mysqlConfiguration(revision: Long) = DatabaseConfigurationView(revision, DatabaseType.MYSQL, null, null, "db.internal", 3306, "qqbot", "qqbot_app", DatabaseSslMode.PREFERRED, 5000, true, "MySQL", "8.4", false, NOW)
    private fun successfulTest(type: DatabaseType) = DatabaseTestResult(true, type, if (type == DatabaseType.POSTGRESQL) "PostgreSQL" else "MySQL", if (type == DatabaseType.POSTGRESQL) "15.8" else "8.4", 17, true, true, DatabaseSchemaState.READY, "3", false, false, 2, NOW)
    private fun postgresCandidateJson() = """{"type":"POSTGRESQL","host":"postgres.internal","port":5432,"databaseName":"qqbot","username":"qqbot_app","password":"$PASSWORD","sslMode":"PREFERRED","connectTimeoutMs":5000}"""
    private fun switchJson() = """{"expectedRevision":4,"candidate":{"type":"MYSQL","host":"db.internal","port":3306,"databaseName":"qqbot","username":"qqbot_app","password":"$PASSWORD","sslMode":"PREFERRED","connectTimeoutMs":5000}}"""
    private fun anySettings(): DatabaseSettings = any(DatabaseSettings::class.java) ?: DatabaseSettings(
        DatabaseType.SQLITE, "unused.db", 1_000, null, null, null, null, null, null, null,
    )
    private fun <T : Any> eqValue(value: T): T = eq(value) ?: value
    companion object { private const val PASSWORD = "database-password-secret"; private val NOW = Instant.parse("2026-07-17T04:00:00Z") }
}
