package com.mieai.qqbot.runtime.outbox

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/** Durable representation of an explicit QQ message reference. */
data class OutboundMessageReference @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @JsonProperty("messageId") val messageId: String,
    @JsonProperty("ignoreGetMessageError") val ignoreGetMessageError: Boolean = false,
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
