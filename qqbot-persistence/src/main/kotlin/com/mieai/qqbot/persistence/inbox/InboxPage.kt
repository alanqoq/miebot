package com.mieai.qqbot.persistence.inbox

/** One immutable page of Inbox events and an optional cursor for the next page. */
data class InboxPage private constructor(
    val events: List<InboxEvent>,
    val nextCursor: String?,
    private val normalized: Boolean,
) {
    constructor(events: List<InboxEvent>, nextCursor: String?) : this(
        events.toList(),
        nextCursor,
        true,
    )
}
