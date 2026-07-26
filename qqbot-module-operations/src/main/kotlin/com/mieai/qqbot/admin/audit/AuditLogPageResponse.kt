package com.mieai.qqbot.admin.audit

import java.time.Instant

data class AuditLogPageResponse private constructor(
    val items: List<AuditLogResponse>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val observedAt: Instant,
    private val normalized: Boolean,
) {
    constructor(
        items: List<AuditLogResponse>,
        nextCursor: String?,
        hasMore: Boolean,
        observedAt: Instant,
    ) : this(
        items.toList(),
        nextCursor,
        hasMore,
        observedAt,
        true,
    )

}
