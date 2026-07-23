package com.mieai.qqbot.admin.plugins;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdatePluginBindingRequest(
        @PositiveOrZero long expectedRevision,
        @NotBlank @Size(max = 65536) String configJson,
        boolean enabled) {}
