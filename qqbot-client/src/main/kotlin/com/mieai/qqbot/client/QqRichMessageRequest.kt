package com.mieai.qqbot.client

/** Extensible rich-message envelope shared by all supported QQ message targets. */
class QqRichMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val kind: QqRichMessageKind,
    payload: Map<String, Any>,
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    val payload: Map<String, Any> = payload.toMap()

    init {
        require(targetId.isNotBlank() && targetId == targetId.trim() &&
            targetId.codePoints().noneMatch(Character::isWhitespace)) {
            "targetId is invalid"
        }
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(messageSequence >= 1) { "messageSequence must be positive" }
        if (kind == QqRichMessageKind.KEYBOARD) validateKeyboardPayload(payload)
    }

    private companion object {
        fun validateKeyboardPayload(value: Map<String, Any>) {
            val markdown = value["markdown"]
            val keyboard = value["keyboard"]
            require(value.keys == setOf("markdown", "keyboard") && markdown is Map<*, *> &&
                keyboard is Map<*, *> && keyboard.isNotEmpty()) {
                "keyboard payload must contain markdown and keyboard objects"
            }
            val content = markdown["content"]
            val template = markdown["custom_template_id"]
            val hasContent = content is String && content.isNotBlank()
            val hasTemplate = template is String && template.isNotBlank()
            require(hasContent || hasTemplate) {
                "keyboard markdown must contain content or custom_template_id"
            }
        }
    }
}
