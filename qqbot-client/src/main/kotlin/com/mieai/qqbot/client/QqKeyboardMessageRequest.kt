package com.mieai.qqbot.client

class QqKeyboardMessageRequest(
    val targetType: QqMessageTargetType,
    val targetId: String,
    val markdownContent: String,
    val keyboardId: String? = null,
    rows: List<Map<String, Any>> = emptyList(),
    val replyMessageId: String?,
    val replyEventId: String?,
    val messageSequence: Int,
) {
    val rows: List<Map<String, Any>> = rows.map { it.toMap() }

    init {
        require(targetId.isNotBlank()) { "targetId is invalid" }
        require(markdownContent.isNotBlank() && markdownContent.length <= 4000) {
            "markdownContent is invalid"
        }
        require(keyboardId != null || rows.isNotEmpty()) { "keyboardId or rows is required" }
        require(messageSequence >= 1) { "messageSequence must be positive" }
    }
}
