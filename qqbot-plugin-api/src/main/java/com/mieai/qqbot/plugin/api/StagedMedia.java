package com.mieai.qqbot.plugin.api;

import java.util.Objects;
import java.util.UUID;

/** Opaque host-owned media handle; the plugin never receives a filesystem path. */
public record StagedMedia(UUID id, MediaKind kind, String fileName, long sizeBytes) {
    public StagedMedia {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName is invalid");
        if (sizeBytes < 1L) throw new IllegalArgumentException("sizeBytes must be positive");
    }
}
