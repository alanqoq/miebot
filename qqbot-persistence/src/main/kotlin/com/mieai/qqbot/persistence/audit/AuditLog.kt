package com.mieai.qqbot.persistence.audit

import java.time.Instant
import java.util.UUID

/** Sanitized administrator mutation record; request bodies are never stored. */
data class AuditLog(
    val id: UUID,
    val actorUsername: String?,
    val action: String,
    val resourcePath: String,
    val outcomeStatus: Int,
    val remoteAddress: String?,
    val traceId: String?,
    val createdAt: Instant,
) {
    init {
        validateNullable(actorUsername, "actorUsername", 64)
        requireText(action, "action", 16)
        requireText(resourcePath, "resourcePath", 512)
        require(outcomeStatus in 100..599) { "outcomeStatus must be a valid HTTP status" }
        validateNullable(remoteAddress, "remoteAddress", 128)
        validateNullable(traceId, "traceId", 128)
    }

    companion object {
        private fun requireText(value: String, name: String, maximumLength: Int) {
            require(value.isNotBlank() && value.length <= maximumLength &&
                value.codePoints().noneMatch { Character.isISOControl(it) }) {
                "$name is invalid"
            }
        }

        private fun validateNullable(value: String?, name: String, maximumLength: Int) {
            value?.let { requireText(it, name, maximumLength) }
        }
    }
}
