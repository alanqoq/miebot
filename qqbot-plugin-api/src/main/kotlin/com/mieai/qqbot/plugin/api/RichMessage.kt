package com.mieai.qqbot.plugin.api

import java.util.UUID

/** Structured rich-message command; payload keys follow the QQ OpenAPI object for its kind. */
class RichMessage(
    val target: MessageTarget,
    val kind: RichMessageKind,
    payload: Map<String, Any>,
    val replyMessageId: String? = null,
    val replyEventId: String? = null,
    val messageSequence: Int = 1,
    val deduplicationKey: String? = null,
    val sourceEventId: UUID? = null,
) {
    val payload: Map<String, Any> = payload.toMap()

    init {
        require(this.payload.isNotEmpty()) { "payload must not be empty" }
        if (kind == RichMessageKind.KEYBOARD) {
            validateKeyboardPayload(this.payload)
        }
        require(messageSequence >= 1) { "messageSequence must be positive" }
        deduplicationKey?.let { value ->
            require(value.isNotBlank() && value.length <= 512 && value.codePoints().noneMatch { Character.isWhitespace(it) }) {
                "deduplicationKey is invalid"
            }
        }
    }

    companion object {
        private fun validateKeyboardPayload(value: Map<String, Any>) {
            val markdown = value["markdown"] as? Map<*, *>
            val keyboard = value["keyboard"] as? Map<*, *>
            if (value.keys != setOf("markdown", "keyboard") || markdown == null || keyboard == null || keyboard.isEmpty()) {
                throw IllegalArgumentException("keyboard payload must contain markdown and keyboard objects")
            }
            val content = markdown["content"]
            val template = markdown["custom_template_id"]
            val hasContent = content is String && content.isNotBlank()
            val hasTemplate = template is String && template.isNotBlank()
            if (!hasContent && !hasTemplate) {
                throw IllegalArgumentException("keyboard markdown must contain content or custom_template_id")
            }
        }
    }
}
