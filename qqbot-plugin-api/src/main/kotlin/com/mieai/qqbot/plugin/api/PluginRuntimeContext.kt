package com.mieai.qqbot.plugin.api

/** Complete binding-scoped capability context supplied to a robot plugin factory. */
data class PluginRuntimeContext(
    val base: PluginContext,
    val configuration: ConfigSnapshot,
    val events: EventService,
    val scheduler: PluginScheduler,
    val httpClient: PluginHttpClient,
    val mediaService: MediaService,
) {
    /** Resolves the token for the callback currently executing on this thread. */
    fun cancellationToken(): CancellationToken = CancellationToken.current()
}
