package com.mieai.qqbot.admin.plugins

import java.time.Instant

class PluginInventoryResponse(
    items: List<PluginArtifactResponse>,
    val directory: String,
    val directoryExists: Boolean,
    val runtimeAvailable: Boolean,
    val scanError: String?,
    val scannedAt: Instant,
) {
    val items: List<PluginArtifactResponse> = items.toList()
}
