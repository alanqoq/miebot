package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.plugin.api.ConfigSnapshot;
import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginRuntimeContext;
import com.mieai.qqbot.plugin.api.PluginHttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Complete capability fixture for constructing a plugin instance in unit tests. */
public final class PluginTestContext implements AutoCloseable {
    private final FakeMessageSender messages;
    private final FakePluginStorage storage;
    private final FakePluginLogger logger;
    private final FakeEventService events;
    private final ManualPluginScheduler scheduler;
    private final FakeRestrictedHttpClient http;
    private final FakeMediaService media;
    private final PluginRuntimeContext context;

    public PluginTestContext(String pluginId, String configurationJson) {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        messages = new FakeMessageSender(clock);
        storage = new FakePluginStorage();
        logger = new FakePluginLogger();
        events = new FakeEventService();
        scheduler = new ManualPluginScheduler();
        http = new FakeRestrictedHttpClient(new PluginHttpResponse(200, Map.of(),
                "{}".getBytes(StandardCharsets.UTF_8)));
        media = new FakeMediaService(clock);
        BotId botId = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        Path dataDirectory = Path.of("build", "plugin-test-data", botId.toString(), pluginId)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create plugin test data directory", exception);
        }
        PluginContext base = new PluginContext(
                botId, BotEnvironment.SANDBOX, pluginId, dataDirectory,
                configurationJson, messages, logger, storage);
        context = new PluginRuntimeContext(base, new ConfigSnapshot(configurationJson, 0L, clock.instant()),
                events, scheduler, http, media);
    }

    public PluginRuntimeContext context() { return context; }
    public FakeMessageSender messages() { return messages; }
    public FakePluginStorage storage() { return storage; }
    public FakePluginLogger logger() { return logger; }
    public FakeEventService events() { return events; }
    public ManualPluginScheduler scheduler() { return scheduler; }
    public FakeRestrictedHttpClient http() { return http; }
    public FakeMediaService media() { return media; }

    @Override public void close() { events.close(); scheduler.close(); }
}
