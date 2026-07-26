package com.mieai.qqbot.plugin.api

import java.time.Instant
import java.util.UUID

/** Persistent delivery result for one message previously accepted by the Outbox. */
data class MessageDeliveryReceipt(
    val jobId: UUID,
    val state: MessageDeliveryState,
    val platformMessageId: String?,
    val platformMessageSequence: Int?,
    val platformTimestamp: String?,
    val completedAt: Instant?,
    val lastError: String?,
) {
    init {
        platformMessageId?.let { requireToken(it, "platformMessageId", 255) }
        platformMessageSequence?.let {
            require(it > 0) { "platformMessageSequence must be positive" }
        }
        platformTimestamp?.let { requireToken(it, "platformTimestamp", 128) }
        lastError?.let {
            require(it.isNotBlank()) { "lastError must not be blank" }
        }
        require(state.isTerminal == (completedAt != null)) {
            "terminal delivery state and completedAt must be consistent"
        }
        require(
            state == MessageDeliveryState.SUCCEEDED ||
                (platformMessageId == null && platformMessageSequence == null && platformTimestamp == null),
        ) { "only succeeded deliveries may contain a platform receipt" }
    }

    private fun requireToken(value: String, name: String, maxCharacters: Int) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value == value.trim()) { "$name must not have surrounding whitespace" }
        require(value.none(Char::isWhitespace)) { "$name must not contain whitespace" }
        require(value.none(Char::isISOControl)) { "$name must not contain control characters" }
        require(value.codePointCount(0, value.length) <= maxCharacters) {
            "$name must not exceed $maxCharacters characters"
        }
    }

    private val MessageDeliveryState.isTerminal: Boolean
        get() = this == MessageDeliveryState.SUCCEEDED ||
            this == MessageDeliveryState.RESULT_UNKNOWN ||
            this == MessageDeliveryState.DEAD_LETTER
}
