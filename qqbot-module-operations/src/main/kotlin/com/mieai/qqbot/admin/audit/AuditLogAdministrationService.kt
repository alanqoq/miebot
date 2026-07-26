package com.mieai.qqbot.admin.audit

import com.mieai.qqbot.persistence.audit.AuditLog
import com.mieai.qqbot.persistence.audit.AuditLogRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class AuditLogAdministrationService(
    private val repository: AuditLogRepository,
) {
    fun append(
        actor: String?,
        action: String,
        path: String,
        status: Int,
        remoteAddress: String?,
        traceId: String?,
    ) {
        repository.append(
            AuditLog(
                UUID.randomUUID(),
                optional(actor, 64),
                requireText(action, 16),
                requireText(path, 512),
                status,
                optional(remoteAddress, 128),
                optional(traceId, 128),
                Instant.now(),
            ),
        )
    }

    fun list(limit: String?, cursor: String?, actor: String?, action: String?): AuditLogPageResponse {
        val page = repository.query(
            parseLimit(limit),
            text(cursor, "cursor", 512),
            text(actor, "actor", 64),
            text(action, "action", 16),
        )
        return AuditLogPageResponse(
            page.logs.map(AuditLogResponse::from),
            page.nextCursor,
            page.nextCursor != null,
            Instant.now(),
        )
    }

    private companion object {
        fun parseLimit(value: String?): Int {
            val candidate = if (value.isNullOrBlank()) "50" else value.trim()
            try {
                val parsed = candidate.toInt()
                require(parsed in 1..100) { "limit must be between 1 and 100" }
                return parsed
            } catch (exception: NumberFormatException) {
                throw IllegalArgumentException("limit must be an integer", exception)
            }
        }

        fun text(value: String?, name: String, max: Int): String? =
            if (value.isNullOrBlank()) null else requireText(value.trim(), max, name)

        fun optional(value: String?, max: Int): String? =
            if (value.isNullOrBlank()) null else requireText(value, max)

        fun requireText(value: String, max: Int, name: String = "value"): String {
            if (value.isBlank() || value.length > max || value.codePoints().anyMatch(Character::isISOControl)) {
                throw IllegalArgumentException("$name is invalid")
            }
            return value
        }
    }
}
