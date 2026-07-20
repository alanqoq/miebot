package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.client.QqMessageTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.UUID;

public record SendBotMessageRequest(
        @NotNull BotMessageKind kind,
        @NotNull QqMessageTargetType targetType,
        @NotBlank String targetId,
        String content,
        QqMediaKind mediaKind,
        String mediaUrl,
        UUID mediaAssetId,
        Map<String, Object> payload,
        String replyMessageId,
        String replyEventId,
        @Positive Integer messageSequence) {
    public SendBotMessageRequest {
        if (messageSequence == null) messageSequence = 1;
    }
}
