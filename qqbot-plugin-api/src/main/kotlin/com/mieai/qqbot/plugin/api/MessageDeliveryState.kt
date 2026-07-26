package com.mieai.qqbot.plugin.api

/** Durable lifecycle state of a message accepted by the framework Outbox. */
enum class MessageDeliveryState {
    PENDING,
    IN_PROGRESS,
    RETRY_WAIT,
    SUCCEEDED,
    RESULT_UNKNOWN,
    DEAD_LETTER,
}
