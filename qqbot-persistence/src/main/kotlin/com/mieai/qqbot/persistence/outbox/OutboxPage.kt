package com.mieai.qqbot.persistence.outbox

/** One immutable page of Outbox jobs and an optional cursor for the next page. */
data class OutboxPage private constructor(
    val jobs: List<OutboxJob>,
    val nextCursor: String?,
    private val normalized: Boolean,
) {
    constructor(jobs: List<OutboxJob>, nextCursor: String?) : this(
        jobs.toList(),
        nextCursor,
        true,
    )
}
