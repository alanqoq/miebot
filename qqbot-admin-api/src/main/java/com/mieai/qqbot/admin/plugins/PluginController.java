package com.mieai.qqbot.admin.plugins;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final PluginAdministrationService service;

    public PluginController(PluginAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public ResponseEntity<PluginInventoryResponse> list(
            @RequestParam(required = false) String query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.scan(query));
    }

    @PostMapping("/reload")
    public ResponseEntity<PluginInventoryResponse> reload(
            @RequestParam(required = false) String query) {
        service.reload();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.scan(query));
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-Plugin-Upload-Confirm", required = false) String confirmation) {
        if (!"trusted-jar".equals(confirmation)) {
            throw new PluginAdministrationException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "PLUGIN_UPLOAD_CONFIRMATION_REQUIRED",
                    "上传前必须确认该 JAR 来自可信来源");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.upload(file));
    }
}
