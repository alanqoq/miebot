package com.mieai.qqbot.client

class QqEmbedMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val title: String,
    val prompt: String?,
    fields: List<Map<String, Any>>,
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    val fields: List<Map<String, Any>> = fields.map { it.toMap() }

    init {
        require(targetId.isNotBlank()) { "targetId is invalid" }
        require(title.isNotBlank() && title.length <= 256) { "title is invalid" }
        require(prompt == null || prompt.length <= 512) { "prompt is too long" }
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }
}
