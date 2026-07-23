package com.mieai.qqbot.admin.plugins;

import java.time.Instant;

/** Read-only metadata for a plugin artifact discovered in the mounted directory. */
public record PluginArtifactResponse(
        String id,
        String name,
        String version,
        String apiCompatibility,
        String fileName,
        long sizeBytes,
        Instant modifiedAt,
        String sha256,
        String status,
        String error,
        String defaultConfigJson,
        boolean loaded,
        int bindingCount,
        int enabledBindingCount) {
}
