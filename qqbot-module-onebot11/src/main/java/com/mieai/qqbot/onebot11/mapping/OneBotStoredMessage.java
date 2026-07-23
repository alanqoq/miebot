package com.mieai.qqbot.onebot11.mapping;

import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.domain.bot.BotId;

public record OneBotStoredMessage(
        int messageId,
        BotId botId,
        String officialMessageId,
        QqMessageTargetType targetType,
        String targetRawId,
        Direction direction,
        String messageType,
        long eventTime,
        long userId,
        String messageJson,
        String senderJson) {

    public enum Direction {
        INCOMING,
        OUTGOING
    }
}
