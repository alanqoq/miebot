package com.mieai.qqbot.admin.events

import java.time.Instant

/** Cursor page returned by both the Outbox and DLQ administration endpoints. */
data class OutboxPageResponse private constructor(
    val items: List<OutboxJobSummaryResponse>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val observedAt: Instant,
    val stats: OutboxQueueStatsResponse,
    private val normalized: Boolean,
) {
    constructor(
        items: List<OutboxJobSummaryResponse>,
        nextCursor: String?,
        hasMore: Boolean,
        observedAt: Instant,
        stats: OutboxQueueStatsResponse,
    ) : this(
        items.toList(),
        nextCursor,
        hasMore,
        observedAt,
        stats,
        true,
    )

    init {
        require(hasMore || nextCursor == null) { "nextCursor must be null when hasMore is false" }
        require(!hasMore || !nextCursor.isNullOrBlank()) { "nextCursor is required when hasMore is true" }
    }

}
