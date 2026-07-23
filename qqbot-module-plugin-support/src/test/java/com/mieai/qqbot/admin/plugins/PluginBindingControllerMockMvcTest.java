package com.mieai.qqbot.admin.plugins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

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
    @MockitoBean PluginBindingFileService files;

    @Test
    void rejectsUnauthenticatedBindingRead() throws Exception {
        mockMvc.perform(get("/api/plugin-bindings"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void reportsMalformedBindingIdAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/plugin-bindings/not-a-uuid/files"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'bindingId'"));

        verifyNoInteractions(service, files);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void supportsBindingCrudAndUsesNoSecretFields() throws Exception {
        PluginBindingResponse response = new PluginBindingResponse(BINDING, "echo", BOT,
                true, 0, Instant.parse("2026-07-19T00:00:00Z"),
                Instant.parse("2026-07-19T00:00:00Z"), "ACTIVE", null);
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
                        .content("{\"expectedRevision\":0,\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/plugin-bindings/{id}", BINDING).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).delete(BINDING);
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void listsAndReadsBindingFiles() throws Exception {
        Instant modifiedAt = Instant.parse("2026-07-23T01:02:03Z");
        PluginFileEntryResponse entry = new PluginFileEntryResponse(
                "settings.json", "assets/settings.json", false, 18, modifiedAt, "application/json");
        when(files.list(BINDING, "assets"))
                .thenReturn(new PluginFileListingResponse("assets", List.of(entry)));
        when(files.content(BINDING, "assets/settings.json"))
                .thenReturn(new PluginFileContentResponse(
                        "assets/settings.json", "{\"enabled\":true}", "abc123", modifiedAt));

        mockMvc.perform(get("/api/plugin-bindings/{id}/files", BINDING)
                        .param("path", "assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("assets"))
                .andExpect(jsonPath("$.entries[0].name").value("settings.json"))
                .andExpect(jsonPath("$.entries[0].path").value("assets/settings.json"))
                .andExpect(jsonPath("$.entries[0].directory").value(false))
                .andExpect(jsonPath("$.entries[0].sizeBytes").value(18));

        mockMvc.perform(get("/api/plugin-bindings/{id}/files/content", BINDING)
                        .param("path", "assets/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("assets/settings.json"))
                .andExpect(jsonPath("$.content").value("{\"enabled\":true}"))
                .andExpect(jsonPath("$.sha256").value("abc123"));

        verify(files).list(BINDING, "assets");
        verify(files).content(BINDING, "assets/settings.json");
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void savesCreatesUploadsAndDeletesBindingFiles() throws Exception {
        Instant modifiedAt = Instant.parse("2026-07-23T01:02:03Z");
        PluginFileContentResponse saved = new PluginFileContentResponse(
                "config.json", "{\"enabled\":true}", "new-hash", modifiedAt);
        PluginFileEntryResponse directory = new PluginFileEntryResponse(
                "assets", "assets", true, 0, modifiedAt, null);
        PluginFileEntryResponse uploaded = new PluginFileEntryResponse(
                "sound.ogg", "assets/sound.ogg", false, 3, modifiedAt, "audio/ogg");
        when(files.saveContent(eq(BINDING), any(UpdatePluginFileContentRequest.class))).thenReturn(saved);
        when(files.createEntry(eq(BINDING), any(CreatePluginFileEntryRequest.class))).thenReturn(directory);
        when(files.upload(eq(BINDING), eq("assets"), any(MultipartFile.class), eq(true), eq("old-hash")))
                .thenReturn(uploaded);

        mockMvc.perform(put("/api/plugin-bindings/{id}/files/content", BINDING).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"config.json\",\"content\":\"{\\\"enabled\\\":true}\","
                                + "\"expectedSha256\":\"old-hash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("config.json"))
                .andExpect(jsonPath("$.sha256").value("new-hash"));

        mockMvc.perform(post("/api/plugin-bindings/{id}/files/entries", BINDING).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"assets\",\"directory\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("assets"))
                .andExpect(jsonPath("$.directory").value(true));

        MockMultipartFile file = new MockMultipartFile(
                "file", "sound.ogg", "audio/ogg", new byte[] {1, 2, 3});
        mockMvc.perform(multipart("/api/plugin-bindings/{id}/files/upload", BINDING)
                        .file(file)
                        .param("directory", "assets")
                        .param("overwrite", "true")
                        .param("expectedSha256", "old-hash")
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("assets/sound.ogg"))
                .andExpect(jsonPath("$.sizeBytes").value(3));

        mockMvc.perform(delete("/api/plugin-bindings/{id}/files", BINDING).with(csrf())
                        .param("path", "assets/sound.ogg"))
                .andExpect(status().isNoContent());

        verify(files).saveContent(eq(BINDING), argThat(request ->
                "config.json".equals(request.getPath())
                        && "{\"enabled\":true}".equals(request.getContent())
                        && "old-hash".equals(request.getExpectedSha256())));
        verify(files).createEntry(eq(BINDING), argThat(request ->
                "assets".equals(request.getPath()) && request.getDirectory()));
        verify(files).upload(eq(BINDING), eq("assets"), argThat(upload ->
                "sound.ogg".equals(upload.getOriginalFilename())), eq(true), eq("old-hash"));
        verify(files).delete(BINDING, "assets/sound.ogg");
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void downloadsBindingFileWithoutCaching(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("notes.txt");
        Files.writeString(source, "download-body", StandardCharsets.UTF_8);
        when(files.download(BINDING, "notes.txt"))
                .thenReturn(new PluginFileDownload(source, "notes.txt", "text/plain"));

        mockMvc.perform(get("/api/plugin-bindings/{id}/files/download", BINDING)
                        .param("path", "notes.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("notes.txt")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes("download-body".getBytes(StandardCharsets.UTF_8)));

        verify(files).download(BINDING, "notes.txt");
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = "ADMIN")
    void reportsPlatformUploadLimitWithoutCallingTheFileAJar() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", new byte[] {1, 2, 3});
        when(files.upload(eq(BINDING), eq(""), any(MultipartFile.class), eq(false), eq(null)))
                .thenThrow(new MaxUploadSizeExceededException(256L * 1024L * 1024L));

        mockMvc.perform(multipart("/api/plugin-bindings/{id}/files/upload", BINDING)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("上传请求不能超过平台上限 256 MiB"));
    }
}
