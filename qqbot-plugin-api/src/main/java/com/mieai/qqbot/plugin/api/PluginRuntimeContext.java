package com.mieai.qqbot.plugin.api;

import java.util.Objects;

/** Complete binding-scoped capability context supplied to a robot plugin factory. */
public record PluginRuntimeContext(
        PluginContext base,
        ConfigSnapshot configuration,
        EventService events,
        PluginScheduler scheduler,
        RestrictedHttpClient httpClient,
        MediaService mediaService) {
    public PluginRuntimeContext {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        Objects.requireNonNull(mediaService, "mediaService must not be null");
    }

    /** Resolves the token for the callback currently executing on this thread. */
    public CancellationToken cancellationToken() {
        return CancellationToken.current();
    }

}
