package com.mieai.qqbot.onebot11.mapping

import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.domain.bot.BotId

data class OneBotStoredMessage(
    val messageId: Int,
    val botId: BotId,
    val officialMessageId: String,
    val targetType: QqMessageTargetType,
    val targetRawId: String,
    val direction: Direction,
    val messageType: String,
    val eventTime: Long,
    val userId: Long,
    val messageJson: String,
    val senderJson: String,
) {
    enum class Direction {
        INCOMING,
        OUTGOING,
    }
}
