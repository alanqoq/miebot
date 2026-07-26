package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration
import com.mieai.qqbot.admin.web.TraceIdFilter
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

@WebMvcTest(controllers = [PluginController::class])
@ContextConfiguration(
    classes = [
        PluginController::class,
        ApiExceptionHandler::class,
        AdminSecurityConfiguration::class,
        TraceIdFilter::class,
    ],
)
class PluginControllerMockMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: PluginAdministrationService

    @Test
    fun `rejects unauthenticated inventory read`() {
        mockMvc.perform(get("/api/plugins"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `returns no store inventory and passes search`() {
        `when`(service.scan("support")).thenReturn(
            PluginInventoryResponse(
                listOf(
                    PluginArtifactResponse(
                        "support",
                        "Support",
                        "1.0",
                        "1",
                        "support.jar",
                        10,
                        Instant.parse("2026-07-18T12:00:00Z"),
                        "hash",
                        "DISCOVERED",
                        null,
                        "{}",
                        false,
                        0,
                        0,
                    ),
                ),
                "/plugins",
                true,
                false,
                null,
                Instant.parse("2026-07-18T12:00:00Z"),
            ),
        )

        mockMvc.perform(get("/api/plugins").param("query", "support"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].id").value("support"))
            .andExpect(jsonPath("$.runtimeAvailable").value(false))

        verify(service).scan("support")
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `requires trusted JAR confirmation for upload`() {
        val file = MockMultipartFile(
            "file",
            "support.jar",
            "application/java-archive",
            byteArrayOf(1, 2, 3),
        )

        mockMvc.perform(multipart("/api/plugins/upload").file(file).with(csrf()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PLUGIN_UPLOAD_CONFIRMATION_REQUIRED"))

        verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `uploads trusted JAR and returns the hot upgrade result without caching`() {
        val file = MockMultipartFile(
            "file",
            "support.jar",
            "application/java-archive",
            byteArrayOf(1, 2, 3),
        )
        val artifact = PluginArtifactResponse(
            "support",
            "Support",
            "2.0.0",
            "1.1.0",
            "support-2.0.0-hash.jar",
            3,
            Instant.parse("2026-07-19T12:00:00Z"),
            "abcdef",
            "LOADED",
            null,
            "{}",
            true,
            2,
            2,
        )
        `when`(service.upload(matchAny(MultipartFile::class.java, file)))
            .thenReturn(PluginUploadResponse("UPGRADED", artifact, "1.0.0", "oldhash"))

        mockMvc.perform(
            multipart("/api/plugins/upload")
                .file(file)
                .header("X-Plugin-Upload-Confirm", "trusted-jar")
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.operation").value("UPGRADED"))
            .andExpect(jsonPath("$.artifact.id").value("support"))
            .andExpect(jsonPath("$.artifact.loaded").value(true))
            .andExpect(jsonPath("$.previousVersion").value("1.0.0"))

        verify(service).upload(matchAny(MultipartFile::class.java, file))
    }
}
