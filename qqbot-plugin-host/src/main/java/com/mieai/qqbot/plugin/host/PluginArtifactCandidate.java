package com.mieai.qqbot.plugin.host;

import java.nio.file.Path;
import java.util.Objects;

/** Validated artifact metadata used before an uploaded JAR is installed. */
public record PluginArtifactCandidate(
        String pluginId,
        String name,
        String version,
        String apiCompatibility,
        String fileName,
        String sha256,
        Path path) {
    public PluginArtifactCandidate {
        Objects.requireNonNull(pluginId, "pluginId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(apiCompatibility, "apiCompatibility must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        path = Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize();
    }
}
