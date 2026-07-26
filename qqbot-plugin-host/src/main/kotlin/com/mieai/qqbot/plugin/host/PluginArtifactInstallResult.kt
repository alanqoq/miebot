package com.mieai.qqbot.plugin.host

data class PluginArtifactInstallResult(
    val operation: String,
    val artifact: LoadedPluginMetadata,
    val previousVersion: String?,
    val previousSha256: String?,
)
