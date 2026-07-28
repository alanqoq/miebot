package com.mieai.qqbot.plugin.api

/** Explicit QQ message reference rendered independently from passive reply metadata. */
data class MessageReference(
    val messageId: String,
    val ignoreGetMessageError: Boolean = false,
) {
    init {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(messageId == messageId.trim()) { "messageId must not have surrounding whitespace" }
        require(messageId.none(Char::isWhitespace)) { "messageId must not contain whitespace" }
        require(messageId.none(Char::isISOControl)) { "messageId must not contain control characters" }
        require(messageId.codePointCount(0, messageId.length) <= 255) {
            "messageId must not exceed 255 characters"
        }
    }
}
