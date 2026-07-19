package com.mieai.qqbot.plugin.host;

import java.util.Objects;
import java.util.Optional;

public record PluginArtifactInstallResult(
        String operation,
        LoadedPluginMetadata artifact,
        Optional<String> previousVersion,
        Optional<String> previousSha256) {
    public PluginArtifactInstallResult {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(artifact, "artifact must not be null");
        Objects.requireNonNull(previousVersion, "previousVersion must not be null");
        Objects.requireNonNull(previousSha256, "previousSha256 must not be null");
    }
}
