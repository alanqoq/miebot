package com.mieai.qqbot.persistence.audit

data class AuditLogPage private constructor(
    val logs: List<AuditLog>,
    val nextCursor: String?,
    private val normalized: Boolean,
) {
    constructor(logs: List<AuditLog>, nextCursor: String?) : this(
        logs.toList(),
        nextCursor,
        true,
    )
}
