package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.plugin.host.PluginArtifactInstallResult

data class PluginUploadResponse(
    val operation: String,
    val artifact: PluginArtifactResponse,
    val previousVersion: String?,
    val previousSha256: String?,
) {
    companion object {
        fun from(
            result: PluginArtifactInstallResult,
            artifact: PluginArtifactResponse,
        ): PluginUploadResponse = PluginUploadResponse(
            result.operation,
            artifact,
            result.previousVersion,
            result.previousSha256,
        )
    }
}
