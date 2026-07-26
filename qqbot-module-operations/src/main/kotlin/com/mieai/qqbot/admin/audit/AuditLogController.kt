package com.mieai.qqbot.admin.audit

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/audit-logs")
class AuditLogController(
    private val service: AuditLogAdministrationService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "50") limit: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) action: String?,
    ): ResponseEntity<AuditLogPageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.list(limit, cursor, actor, action))
}
