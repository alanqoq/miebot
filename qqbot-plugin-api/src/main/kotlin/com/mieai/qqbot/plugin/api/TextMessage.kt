package com.mieai.qqbot.plugin.api

import java.util.UUID

/** Text-only outbound command. The host persists it before any network call. */
data class TextMessage(
    val target: MessageTarget,
    val content: String,
    val replyMessageId: String? = null,
    val replyEventId: String? = null,
    val messageSequence: Int = 1,
    val deduplicationKey: String? = null,
    val sourceEventId: UUID? = null,
) {
    init {
        require(content.isNotBlank() && content.codePoints().noneMatch(::unsupportedControl)) {
            "content must be non-blank and free of control characters"
        }
        require(content.codePointCount(0, content.length) <= 4000) {
            "content must not exceed 4000 characters"
        }
        require(messageSequence >= 1) { "messageSequence must be positive" }
        deduplicationKey?.let { value ->
            require(value.isNotBlank() && value.length <= 512 && value.codePoints().noneMatch { Character.isWhitespace(it) }) {
                "deduplicationKey is invalid"
            }
        }
    }

    companion object {
        fun reply(event: PluginEvent, content: String): TextMessage {
            val inbound = requireNotNull(event.message) { "event has no reply target" }
            val key = "reply:${event.id}:${Integer.toHexString(content.hashCode())}"
            return TextMessage(
                inbound.replyTarget,
                content,
                inbound.messageId,
                inbound.eventId,
                1,
                key,
                event.id,
            )
        }

        private fun unsupportedControl(value: Int): Boolean =
            Character.isISOControl(value) && value != '\n'.code && value != '\r'.code && value != '\t'.code
    }
}
