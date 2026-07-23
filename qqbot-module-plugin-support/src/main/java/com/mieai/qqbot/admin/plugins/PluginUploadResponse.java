package com.mieai.qqbot.admin.plugins;

import com.mieai.qqbot.plugin.host.PluginArtifactInstallResult;

public record PluginUploadResponse(
        String operation,
        PluginArtifactResponse artifact,
        String previousVersion,
        String previousSha256) {

    static PluginUploadResponse from(PluginArtifactInstallResult result, PluginArtifactResponse artifact) {
        return new PluginUploadResponse(result.operation(), artifact,
                result.previousVersion().orElse(null), result.previousSha256().orElse(null));
    }
}
