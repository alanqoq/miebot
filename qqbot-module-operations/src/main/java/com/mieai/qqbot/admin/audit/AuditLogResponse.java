package com.mieai.qqbot.admin.audit;

import com.mieai.qqbot.persistence.audit.AuditLog;
import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        String actorUsername,
        String action,
        String resourcePath,
        int outcomeStatus,
        String remoteAddress,
        String traceId,
        Instant createdAt) {

    static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.id(), log.actorUsername().orElse(null), log.action(),
                log.resourcePath(), log.outcomeStatus(), log.remoteAddress().orElse(null),
                log.traceId().orElse(null), log.createdAt());
    }
}
