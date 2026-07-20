package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.BotId;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/** Host-owned staging store for bounded web/plugin media uploads. */
public interface MediaAssetStore {
    MediaAsset stage(BotId botId, QqMediaKind kind, String fileName, String contentType,
            InputStream input, long maxBytes);
    Optional<MediaAsset> find(BotId botId, UUID id);
    InputStream open(MediaAsset asset);
    void delete(MediaAsset asset);
}
