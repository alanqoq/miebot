package com.mieai.qqbot.persistence.outbox

/** Validated QQ response persisted atomically with a successful Outbox transition. */
data class OutboxSendReceipt(
    val platformMessageId: String,
    val platformMessageSequence: Int?,
    val platformTimestamp: String?,
) {
    init {
        requireToken(platformMessageId, "platformMessageId", 255)
        platformMessageSequence?.let {
            require(it > 0) { "platformMessageSequence must be positive" }
        }
        platformTimestamp?.let { requireToken(it, "platformTimestamp", 128) }
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
}
