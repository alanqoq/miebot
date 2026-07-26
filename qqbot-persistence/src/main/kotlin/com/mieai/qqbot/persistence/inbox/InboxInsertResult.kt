package com.mieai.qqbot.persistence.inbox

/** Result of an idempotent Inbox insert. */
data class InboxInsertResult(
    val inserted: Boolean,
    val event: InboxEvent,
) {
}
