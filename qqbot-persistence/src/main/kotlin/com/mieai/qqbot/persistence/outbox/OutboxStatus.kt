package com.mieai.qqbot.persistence.outbox

/** Durable lifecycle state of an asynchronous side-effect job. */
enum class OutboxStatus {
    PENDING,
    IN_PROGRESS,
    RETRY_WAIT,
    SUCCEEDED,
    RESULT_UNKNOWN,
    DEAD_LETTER,
    ;

    fun isTerminal(): Boolean = this == SUCCEEDED || this == RESULT_UNKNOWN || this == DEAD_LETTER
}
