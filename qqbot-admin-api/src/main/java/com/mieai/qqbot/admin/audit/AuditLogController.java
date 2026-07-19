package com.mieai.qqbot.admin.audit;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {
    private final AuditLogAdministrationService service;

    public AuditLogController(AuditLogAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public ResponseEntity<AuditLogPageResponse> list(
            @RequestParam(defaultValue = "50") String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(limit, cursor, actor, action));
    }
}
