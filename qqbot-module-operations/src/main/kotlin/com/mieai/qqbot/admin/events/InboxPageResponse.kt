package com.mieai.qqbot.admin.events

import java.time.Instant

data class InboxPageResponse private constructor(
    val items: List<InboxEventSummaryResponse>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val observedAt: Instant,
    private val normalized: Boolean,
) {
    constructor(
        items: List<InboxEventSummaryResponse>,
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

    init {
        require(hasMore || nextCursor == null) { "nextCursor must be null when hasMore is false" }
        require(!hasMore || !nextCursor.isNullOrBlank()) { "nextCursor is required when hasMore is true" }
    }

}
