package com.mieai.qqbot.admin.plugins

import jakarta.validation.Valid
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/plugin-bindings")
class PluginBindingController(
    private val service: PluginBindingAdministrationService,
    private val files: PluginBindingFileService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) pluginId: String?,
        @RequestParam(required = false) botId: String?,
    ) = service.list(pluginId, botId)

    @PostMapping
    fun create(@Valid @RequestBody request: CreatePluginBindingRequest): ResponseEntity<PluginBindingResponse> {
        val response = service.create(request)
        return ResponseEntity.created(URI.create("/api/plugin-bindings/${response.id}")).body(response)
    }

    @PutMapping("/{bindingId}")
    fun update(
        @PathVariable bindingId: UUID,
        @Valid @RequestBody request: UpdatePluginBindingRequest,
    ) = service.update(bindingId, request)

    @DeleteMapping("/{bindingId}")
    fun delete(@PathVariable bindingId: UUID): ResponseEntity<Void> {
        service.delete(bindingId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{bindingId}/reset")
    fun reset(@PathVariable bindingId: UUID) = service.reset(bindingId)

    @GetMapping("/{bindingId}/files")
    fun listFiles(
        @PathVariable bindingId: UUID,
        @RequestParam(defaultValue = "") path: String,
    ) = files.list(bindingId, path)

    @GetMapping("/{bindingId}/files/content")
    fun content(
        @PathVariable bindingId: UUID,
        @RequestParam path: String,
    ) = files.content(bindingId, path)

    @PutMapping("/{bindingId}/files/content")
    fun saveContent(
        @PathVariable bindingId: UUID,
        @Valid @RequestBody request: UpdatePluginFileContentRequest,
    ) = files.saveContent(bindingId, request)

    @PostMapping("/{bindingId}/files/entries")
    fun createEntry(
        @PathVariable bindingId: UUID,
        @Valid @RequestBody request: CreatePluginFileEntryRequest,
    ): ResponseEntity<PluginFileEntryResponse> = ResponseEntity.status(201).body(files.createEntry(bindingId, request))

    @PostMapping("/{bindingId}/files/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @PathVariable bindingId: UUID,
        @RequestParam(defaultValue = "") directory: String,
        @RequestParam(defaultValue = "false") overwrite: Boolean,
        @RequestParam(required = false) expectedSha256: String?,
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<PluginFileEntryResponse> = ResponseEntity.status(201)
        .body(files.upload(bindingId, directory, file, overwrite, expectedSha256))

    @GetMapping("/{bindingId}/files/download")
    fun download(
        @PathVariable bindingId: UUID,
        @RequestParam path: String,
    ): ResponseEntity<Resource> {
        val download = files.download(bindingId, path)
        val mediaType = download.contentType?.let {
            runCatching { MediaType.parseMediaType(it) }.getOrNull()
        } ?: MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(mediaType)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.fileName, StandardCharsets.UTF_8).build().toString(),
            )
            .body(FileSystemResource(download.path))
    }

    @DeleteMapping("/{bindingId}/files")
    fun deleteFile(
        @PathVariable bindingId: UUID,
        @RequestParam path: String,
    ): ResponseEntity<Void> {
        files.delete(bindingId, path)
        return ResponseEntity.noContent().build()
    }
}
