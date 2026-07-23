package com.mieai.qqbot.admin.plugins;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PluginInventoryResponse(
        List<PluginArtifactResponse> items,
        String directory,
        boolean directoryExists,
        boolean runtimeAvailable,
        String scanError,
        Instant scannedAt) {

    public PluginInventoryResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(scannedAt, "scannedAt must not be null");
    }
}
