package com.mieai.qqbot.plugin.host;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record LoadedPluginMetadata(
        String id,
        String name,
        String version,
        String apiCompatibility,
        Path path,
        String sha256,
        String entrypoint,
        String configurationSchema,
        String defaultConfigurationPath,
        String defaultConfiguration,
        Set<String> capabilities) {
    public LoadedPluginMetadata {
        if (defaultConfigurationPath == null || defaultConfigurationPath.isBlank()) {
            throw new IllegalArgumentException("defaultConfigurationPath must not be blank");
        }
        if (defaultConfiguration == null || defaultConfiguration.isBlank()) {
            throw new IllegalArgumentException("defaultConfiguration must not be blank");
        }
        Objects.requireNonNull(path, "path must not be null");
        capabilities = Set.copyOf(capabilities);
    }
}
