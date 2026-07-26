package com.mieai.qqbot.client

class QqMarkdownMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val content: String,
    val customTemplateId: String?,
    params: Map<String, String>,
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    val params: Map<String, String> = params.toMap()

    init {
        require(targetId.isNotBlank()) { "targetId is invalid" }
        require(content.isNotBlank() && content.length <= 4000) { "content is invalid" }
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }
}
