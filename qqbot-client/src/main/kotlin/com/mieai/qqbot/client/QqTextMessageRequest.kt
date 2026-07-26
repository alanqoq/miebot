package com.mieai.qqbot.client

/** Validated text request used by the QQ OpenAPI sender. */
data class QqTextMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val content: String,
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    init {
        requirePathToken(targetId, "targetId")
        require(!content.isBlank() && content.codePoints().noneMatch(::unsupportedControl)) {
            "content must be non-blank and free of control characters"
        }
        require(content.codePointCount(0, content.length) <= 4000) { "content is too long" }
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }

    companion object {
        private fun unsupportedControl(value: Int): Boolean =
            Character.isISOControl(value) && value != '\n'.code && value != '\r'.code && value != '\t'.code

        private fun requirePathToken(value: String, name: String) {
            require(!value.isBlank() && value == value.trim() &&
                value.codePoints().noneMatch(Character::isWhitespace) &&
                '/' !in value && '\\' !in value && '?' !in value && '#' !in value) {
                "$name is invalid"
            }
        }
    }
}
