package com.mieai.qqbot.module.host;

import java.time.Duration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/module-assets")
public class ModuleAssetController {
    private final FrameworkModuleHost host;

    public ModuleAssetController(FrameworkModuleHost host) {
        this.host = host;
    }

    @GetMapping("/{moduleId}/{*assetPath}")
    public ResponseEntity<Resource> asset(
            @PathVariable String moduleId,
            @PathVariable String assetPath) {
        Resource resource = host.findWebAsset(moduleId, assetPath).orElse(null);
        if (resource == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentType(MediaTypeFactory.getMediaType(resource)
                        .orElse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM))
                .body(resource);
    }
}
