package com.mieai.qqbot.client

class QqArkMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val templateId: Int,
    values: List<Map<String, String>>,
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    val values: List<Map<String, String>> = values.map { it.toMap() }

    init {
        require(targetId.isNotBlank()) { "targetId is invalid" }
        require(templateId >= 1) { "templateId must be positive" }
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }
}
