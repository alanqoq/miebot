package com.mieai.qqbot.admin.plugins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(controllers = PluginController.class)
@ContextConfiguration(classes = {
    PluginController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class,
})
class PluginControllerMockMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PluginAdministrationService service;

    @Test
    void rejectsUnauthenticatedInventoryRead() throws Exception {
        mockMvc.perform(get("/api/plugins"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void returnsNoStoreInventoryAndPassesSearch() throws Exception {
        when(service.scan("support")).thenReturn(new PluginInventoryResponse(
                List.of(new PluginArtifactResponse(
                        "support", "Support", "1.0", "1", "support.jar", 10,
                        Instant.parse("2026-07-18T12:00:00Z"), "hash", "DISCOVERED", null,
                        "{}", false, 0, 0)),
                "/plugins", true, false, null, Instant.parse("2026-07-18T12:00:00Z")));

        mockMvc.perform(get("/api/plugins").param("query", "support"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value("support"))
                .andExpect(jsonPath("$.runtimeAvailable").value(false));

        verify(service).scan("support");
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void requiresTrustedJarConfirmationForUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "support.jar", "application/java-archive", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/plugins/upload").file(file).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLUGIN_UPLOAD_CONFIRMATION_REQUIRED"));

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void uploadsTrustedJarAndReturnsTheHotUpgradeResultWithoutCaching() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "support.jar", "application/java-archive", new byte[] {1, 2, 3});
        PluginArtifactResponse artifact = new PluginArtifactResponse(
                "support", "Support", "2.0.0", "1.1.0", "support-2.0.0-hash.jar", 3,
                Instant.parse("2026-07-19T12:00:00Z"), "abcdef", "LOADED", null,
                "{}", true, 2, 2);
        when(service.upload(any(MultipartFile.class))).thenReturn(
                new PluginUploadResponse("UPGRADED", artifact, "1.0.0", "oldhash"));

        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(file)
                        .header("X-Plugin-Upload-Confirm", "trusted-jar")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operation").value("UPGRADED"))
                .andExpect(jsonPath("$.artifact.id").value("support"))
                .andExpect(jsonPath("$.artifact.loaded").value(true))
                .andExpect(jsonPath("$.previousVersion").value("1.0.0"));

        verify(service).upload(any(MultipartFile.class));
    }
}
