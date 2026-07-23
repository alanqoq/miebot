package com.mieai.qqbot.admin.plugins;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePluginBindingRequest(
        @NotBlank @Size(max = 128) String pluginId,
        @NotBlank @Size(max = 36) String botId,
        @NotBlank @Size(max = 65536) String configJson,
        boolean enabled) {}
