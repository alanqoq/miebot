package com.mieai.qqbot.persistence.inbox

/** Durable processing state of a platform event. */
enum class InboxStatus {
    RECEIVED,
    PROCESSING,
    DISPATCHED,
    DEAD_LETTER,
}
