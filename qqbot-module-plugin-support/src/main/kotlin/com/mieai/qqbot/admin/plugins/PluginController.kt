package com.mieai.qqbot.admin.plugins

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/plugins")
class PluginController(
    private val service: PluginAdministrationService,
) {
    @GetMapping
    fun list(@RequestParam(required = false) query: String?): ResponseEntity<PluginInventoryResponse> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.scan(query))

    @PostMapping("/reload")
    fun reload(@RequestParam(required = false) query: String?): ResponseEntity<PluginInventoryResponse> {
        service.reload()
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.scan(query))
    }

    @PostMapping(path = ["/upload"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestHeader(value = "X-Plugin-Upload-Confirm", required = false) confirmation: String?,
    ): ResponseEntity<PluginUploadResponse> {
        if (confirmation != "trusted-jar") {
            throw PluginAdministrationException(
                HttpStatus.BAD_REQUEST,
                "PLUGIN_UPLOAD_CONFIRMATION_REQUIRED",
                "上传前必须确认该 JAR 来自可信来源",
            )
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(service.upload(file))
    }
}
