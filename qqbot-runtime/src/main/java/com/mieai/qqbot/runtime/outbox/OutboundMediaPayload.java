package com.mieai.qqbot.runtime.outbox;

import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.client.QqMessageTargetType;
import java.util.UUID;

/** Durable media-message contract consumed by the production Outbox worker. */
public record OutboundMediaPayload(
        QqMessageTargetType targetType,
        String targetId,
        QqMediaKind mediaKind,
        String mediaUrl,
        String content,
        String replyMessageId,
        String replyEventId,
        int messageSequence,
        UUID mediaAssetId) {
    public OutboundMediaPayload(QqMessageTargetType targetType, String targetId, QqMediaKind mediaKind,
            String mediaUrl, String content, String replyMessageId, String replyEventId, int messageSequence) {
        this(targetType, targetId, mediaKind, mediaUrl, content, replyMessageId, replyEventId, messageSequence, null);
    }

    public static final String JOB_TYPE = "QQ_SEND_MEDIA";
}
