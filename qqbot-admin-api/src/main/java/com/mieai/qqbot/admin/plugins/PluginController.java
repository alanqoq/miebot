package com.mieai.qqbot.admin.plugins;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
