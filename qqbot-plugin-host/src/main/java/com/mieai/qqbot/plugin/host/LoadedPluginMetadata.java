package com.mieai.qqbot.plugin.host;

import java.nio.file.Path;
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
        Set<String> capabilities) {
    public LoadedPluginMetadata {
        capabilities = Set.copyOf(capabilities);
    }
}
