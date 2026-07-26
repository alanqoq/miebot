package com.mieai.qqbot.module.host

import java.time.Duration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/module-assets")
class ModuleAssetController(
    private val host: FrameworkModuleHost,
) {
    @GetMapping("/{moduleId}/{*assetPath}")
    fun asset(
        @PathVariable moduleId: String,
        @PathVariable assetPath: String,
    ): ResponseEntity<Resource> {
        val resource = host.findWebAsset(moduleId, assetPath)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
            .contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
            .body(resource)
    }
}
