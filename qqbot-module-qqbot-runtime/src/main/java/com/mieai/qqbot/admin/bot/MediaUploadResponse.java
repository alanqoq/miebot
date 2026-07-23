package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.client.MediaAsset;
import java.time.Instant;
import java.util.UUID;

public record MediaUploadResponse(UUID id, UUID botId, String kind, String fileName,
        String contentType, long sizeBytes, Instant createdAt) {
    static MediaUploadResponse from(MediaAsset asset) {
        return new MediaUploadResponse(asset.id(), asset.botId().value(), asset.kind().name(),
                asset.fileName(), asset.contentType(), asset.sizeBytes(), asset.createdAt());
    }
}
