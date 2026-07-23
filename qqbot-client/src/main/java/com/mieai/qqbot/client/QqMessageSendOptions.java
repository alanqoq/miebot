package com.mieai.qqbot.client;

import com.mieai.qqbot.protocol.openapi.QqMessageModels.MessageReference;
import java.util.Objects;
import java.util.Optional;

/** Optional fields shared by QQ message sends without breaking the existing request records. */
public record QqMessageSendOptions(
        Optional<MessageReference> messageReference,
        boolean wakeup) {
    private static final QqMessageSendOptions NONE =
            new QqMessageSendOptions(Optional.empty(), false);

    public QqMessageSendOptions {
        Objects.requireNonNull(messageReference, "messageReference must not be null");
    }

    public static QqMessageSendOptions none() {
        return NONE;
    }
}
