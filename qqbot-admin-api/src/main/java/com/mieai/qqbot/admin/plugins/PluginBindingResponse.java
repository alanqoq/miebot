package com.mieai.qqbot.admin.plugins;

import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import java.time.Instant;
import java.util.UUID;

public record PluginBindingResponse(
        UUID id,
        String pluginId,
        UUID botId,
        String configJson,
        boolean enabled,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        String runtimeState,
        String runtimeError) {

    public PluginBindingResponse(UUID id, String pluginId, UUID botId, String configJson,
            boolean enabled, long revision, Instant createdAt, Instant updatedAt) {
        this(id, pluginId, botId, configJson, enabled, revision, createdAt, updatedAt,
                enabled ? "ACTIVE" : "PAUSED", null);
    }
    static PluginBindingResponse from(BotPluginBinding binding) {
        return new PluginBindingResponse(binding.id(), binding.pluginId(), binding.botId().value(),
                binding.configJson(), binding.enabled(), binding.revision(), binding.createdAt(), binding.updatedAt(),
                binding.runtimeState().name(), binding.runtimeError().orElse(null));
    }
}
