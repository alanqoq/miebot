package com.mieai.qqbot.plugin.api;

import java.util.Objects;

/** Extended SDK context used by version-two factories while preserving the original context API. */
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

    public static PluginRuntimeContext legacy(PluginContext context, long revision) {
        return new PluginRuntimeContext(context,
                new ConfigSnapshot(context.configurationJson(), revision, java.time.Instant.now()),
                EventService.denied(), PluginScheduler.denied(), RestrictedHttpClient.denied(),
                MediaService.denied());
    }
}
