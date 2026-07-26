package com.mieai.qqbot.admin.audit

import com.mieai.qqbot.persistence.audit.AuditLog
import java.time.Instant
import java.util.UUID

data class AuditLogResponse(
    val id: UUID,
    val actorUsername: String?,
    val action: String,
    val resourcePath: String,
    val outcomeStatus: Int,
    val remoteAddress: String?,
    val traceId: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(log: AuditLog): AuditLogResponse = AuditLogResponse(
            log.id,
            log.actorUsername,
            log.action,
            log.resourcePath,
            log.outcomeStatus,
            log.remoteAddress,
            log.traceId,
            log.createdAt,
        )
    }
}
