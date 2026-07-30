package com.mieai.qqbot.plugin.api

import java.nio.file.Path

/** Complete binding-scoped capability context supplied to a robot plugin factory. */
data class PluginRuntimeContext(
    val base: PluginContext,
    val configuration: ConfigSnapshot,
    val events: EventService,
    val scheduler: PluginScheduler,
    val httpClient: PluginHttpClient,
    val mediaService: MediaService,
) {
    /** Binding configuration file selected by the plugin manifest. */
    val configurationFile: Path
        get() {
            val file = base.dataDirectory.resolve(configuration.fileName).normalize()
            check(file.parent == base.dataDirectory) { "configuration file escaped the binding data directory" }
            return file
        }

    /** Resolves the token for the callback currently executing on this thread. */
    fun cancellationToken(): CancellationToken = CancellationToken.current()
}
