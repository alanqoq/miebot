package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.plugin.api.ConfigSnapshot
import com.mieai.qqbot.plugin.api.PluginContext
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Complete capability fixture for constructing a plugin instance in unit tests.
 * The configurationJson parameter name is retained for source compatibility, but accepts raw JSON or YAML content.
 */
class PluginTestContext @JvmOverloads constructor(
    pluginId: String,
    configurationJson: String,
    configurationFileName: String = ConfigSnapshot.DEFAULT_FILE_NAME,
) : AutoCloseable {
    val messages: FakeMessageSender
    val storage: FakePluginStorage
    val logger: FakePluginLogger
    val events: FakeEventService
    val scheduler: ManualPluginScheduler
    val http: FakePluginHttpClient
    val media: FakeMediaService
    val context: PluginRuntimeContext

    init {
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        messages = FakeMessageSender(clock)
        storage = FakePluginStorage()
        logger = FakePluginLogger()
        events = FakeEventService()
        scheduler = ManualPluginScheduler()
        http = FakePluginHttpClient {
            PluginHttpResponse(200, emptyMap(), "{}".toByteArray(StandardCharsets.UTF_8))
        }
        media = FakeMediaService(clock)
        val botId = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
        val dataDirectory = Path.of("build", "plugin-test-data", botId.toString(), pluginId)
            .toAbsolutePath().normalize()
        try {
            Files.createDirectories(dataDirectory)
            Files.writeString(dataDirectory.resolve(configurationFileName), configurationJson, StandardCharsets.UTF_8)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to create plugin test data directory", exception)
        }
        val base = PluginContext(
            botId,
            BotEnvironment.SANDBOX,
            pluginId,
            dataDirectory,
            configurationJson,
            messages,
            logger,
            storage,
        )
        context = PluginRuntimeContext(
            base,
            ConfigSnapshot(configurationJson, 0L, clock.instant(), configurationFileName),
            events,
            scheduler,
            http,
            media,
        )
    }

    override fun close() {
        events.close()
        scheduler.close()
    }
}
