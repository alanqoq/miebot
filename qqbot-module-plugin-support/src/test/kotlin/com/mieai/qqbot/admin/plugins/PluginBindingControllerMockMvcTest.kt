package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.admin.error.ApiExceptionHandler
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration
import com.mieai.qqbot.admin.web.TraceIdFilter
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [PluginBindingController::class])
@ContextConfiguration(
    classes = [
        PluginBindingController::class,
        ApiExceptionHandler::class,
        AdminSecurityConfiguration::class,
        TraceIdFilter::class,
    ],
)
class PluginBindingControllerMockMvcTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: PluginBindingAdministrationService

    @MockitoBean
    lateinit var files: PluginBindingFileService

    @Test
    fun `rejects unauthenticated binding read`() {
        mockMvc.perform(get("/api/plugin-bindings"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `reports malformed binding id as bad request`() {
        mockMvc.perform(get("/api/plugin-bindings/not-a-uuid/files"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid value for parameter 'bindingId'"))

        verifyNoInteractions(service, files)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `supports binding CRUD without secret fields`() {
        val response = PluginBindingResponse(
            BINDING,
            "echo",
            BOT,
            true,
            0,
            Instant.parse("2026-07-19T00:00:00Z"),
            Instant.parse("2026-07-19T00:00:00Z"),
            "ACTIVE",
            null,
        )
        val createFallback = CreatePluginBindingRequest("echo", BOT.toString(), "{}", true)
        val updateFallback = UpdatePluginBindingRequest(0, false)
        `when`(service.list(null, null)).thenReturn(listOf(response))
        `when`(service.create(matchAny(CreatePluginBindingRequest::class.java, createFallback))).thenReturn(response)
        `when`(service.update(matchAny(UUID::class.java, BINDING), matchAny(UpdatePluginBindingRequest::class.java, updateFallback)))
            .thenReturn(response)

        mockMvc.perform(get("/api/plugin-bindings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].pluginId").value("echo"))
        mockMvc.perform(
            post("/api/plugin-bindings").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"pluginId":"echo","botId":"$BOT","configJson":"{}","enabled":true}""",
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/api/plugin-bindings/$BINDING"))
        mockMvc.perform(
            put("/api/plugin-bindings/{id}", BINDING).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedRevision":0,"enabled":false}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(delete("/api/plugin-bindings/{id}", BINDING).with(csrf()))
            .andExpect(status().isNoContent)

        verify(service).delete(BINDING)
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `lists and reads binding files`() {
        val modifiedAt = Instant.parse("2026-07-23T01:02:03Z")
        val entry = PluginFileEntryResponse(
            "settings.json",
            "assets/settings.json",
            false,
            18,
            modifiedAt,
            "application/json",
        )
        `when`(files.list(BINDING, "assets"))
            .thenReturn(PluginFileListingResponse("assets", listOf(entry)))
        `when`(files.content(BINDING, "assets/settings.json")).thenReturn(
            PluginFileContentResponse(
                "assets/settings.json",
                "{\"enabled\":true}",
                "abc123",
                modifiedAt,
            ),
        )

        mockMvc.perform(
            get("/api/plugin-bindings/{id}/files", BINDING).param("path", "assets"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.path").value("assets"))
            .andExpect(jsonPath("$.entries[0].name").value("settings.json"))
            .andExpect(jsonPath("$.entries[0].path").value("assets/settings.json"))
            .andExpect(jsonPath("$.entries[0].directory").value(false))
            .andExpect(jsonPath("$.entries[0].sizeBytes").value(18))

        mockMvc.perform(
            get("/api/plugin-bindings/{id}/files/content", BINDING)
                .param("path", "assets/settings.json"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.path").value("assets/settings.json"))
            .andExpect(jsonPath("$.content").value("{\"enabled\":true}"))
            .andExpect(jsonPath("$.sha256").value("abc123"))

        verify(files).list(BINDING, "assets")
        verify(files).content(BINDING, "assets/settings.json")
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `saves, creates, uploads, and deletes binding files`() {
        val modifiedAt = Instant.parse("2026-07-23T01:02:03Z")
        val saved = PluginFileContentResponse(
            "config.json",
            "{\"enabled\":true}",
            "new-hash",
            modifiedAt,
        )
        val createdDirectory = PluginFileEntryResponse(
            "assets",
            "assets",
            true,
            0,
            modifiedAt,
            null,
        )
        val uploaded = PluginFileEntryResponse(
            "sound.ogg",
            "assets/sound.ogg",
            false,
            3,
            modifiedAt,
            "audio/ogg",
        )
        val updateFallback = UpdatePluginFileContentRequest("config.json", "", null)
        val createFallback = CreatePluginFileEntryRequest("assets", true)
        val uploadFallback = MockMultipartFile("file", "fallback", null, byteArrayOf())
        `when`(files.saveContent(matchEq(BINDING), matchAny(UpdatePluginFileContentRequest::class.java, updateFallback)))
            .thenReturn(saved)
        `when`(files.createEntry(matchEq(BINDING), matchAny(CreatePluginFileEntryRequest::class.java, createFallback)))
            .thenReturn(createdDirectory)
        `when`(
            files.upload(
                matchEq(BINDING),
                matchEq("assets"),
                matchAny(MultipartFile::class.java, uploadFallback),
                matchEq(true),
                matchEq("old-hash"),
            ),
        ).thenReturn(uploaded)

        mockMvc.perform(
            put("/api/plugin-bindings/{id}/files/content", BINDING).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"path":"config.json","content":"{\"enabled\":true}","expectedSha256":"old-hash"}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.path").value("config.json"))
            .andExpect(jsonPath("$.sha256").value("new-hash"))

        mockMvc.perform(
            post("/api/plugin-bindings/{id}/files/entries", BINDING).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"path":"assets","directory":true}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.path").value("assets"))
            .andExpect(jsonPath("$.directory").value(true))

        val file = MockMultipartFile("file", "sound.ogg", "audio/ogg", byteArrayOf(1, 2, 3))
        mockMvc.perform(
            multipart("/api/plugin-bindings/{id}/files/upload", BINDING)
                .file(file)
                .param("directory", "assets")
                .param("overwrite", "true")
                .param("expectedSha256", "old-hash")
                .with(csrf()),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.path").value("assets/sound.ogg"))
            .andExpect(jsonPath("$.sizeBytes").value(3))

        mockMvc.perform(
            delete("/api/plugin-bindings/{id}/files", BINDING).with(csrf())
                .param("path", "assets/sound.ogg"),
        ).andExpect(status().isNoContent)

        val update = ArgumentCaptor.forClass(UpdatePluginFileContentRequest::class.java)
        verify(files).saveContent(
            matchEq(BINDING),
            update.capture() ?: UpdatePluginFileContentRequest("unused", "", null),
        )
        assert(update.value.path == "config.json")
        assert(update.value.content == "{\"enabled\":true}")
        assert(update.value.expectedSha256 == "old-hash")

        val create = ArgumentCaptor.forClass(CreatePluginFileEntryRequest::class.java)
        verify(files).createEntry(
            matchEq(BINDING),
            create.capture() ?: CreatePluginFileEntryRequest("unused", false),
        )
        assert(create.value.path == "assets")
        assert(create.value.directory)

        val upload = ArgumentCaptor.forClass(MultipartFile::class.java)
        verify(files).upload(
            matchEq(BINDING),
            matchEq("assets"),
            upload.capture() ?: file,
            matchEq(true),
            matchEq("old-hash"),
        )
        assert(upload.value.originalFilename == "sound.ogg")
        verify(files).delete(BINDING, "assets/sound.ogg")
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `downloads binding file without caching`(@TempDir directory: Path) {
        val source = directory.resolve("notes.txt")
        Files.writeString(source, "download-body", StandardCharsets.UTF_8)
        `when`(files.download(BINDING, "notes.txt"))
            .thenReturn(PluginFileDownload(source, "notes.txt", "text/plain"))

        mockMvc.perform(
            get("/api/plugin-bindings/{id}/files/download", BINDING)
                .param("path", "notes.txt"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("notes.txt")))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().bytes("download-body".toByteArray(StandardCharsets.UTF_8)))

        verify(files).download(BINDING, "notes.txt")
    }

    @Test
    @WithMockUser(username = "alanqaq", roles = ["ADMIN"])
    fun `reports platform upload limit without calling the file a JAR`() {
        val file = MockMultipartFile("file", "archive.zip", "application/zip", byteArrayOf(1, 2, 3))
        val fallback = MockMultipartFile("file", "fallback", null, byteArrayOf())
        `when`(
            files.upload(
                matchEq(BINDING),
                matchEq(""),
                matchAny(MultipartFile::class.java, fallback),
                matchEq(false),
                ArgumentMatchers.isNull(),
            ),
        ).thenThrow(MaxUploadSizeExceededException(256L * 1024L * 1024L))

        mockMvc.perform(
            multipart("/api/plugin-bindings/{id}/files/upload", BINDING)
                .file(file)
                .with(csrf()),
        )
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"))
            .andExpect(jsonPath("$.message").value("上传请求不能超过平台上限 256 MiB"))
    }

    companion object {
        private val BINDING: UUID = UUID.fromString("770e8400-e29b-41d4-a716-446655440001")
        private val BOT: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
    }
}
