package com.mieai.qqbot.plugin.api

import java.util.concurrent.CompletionStage

/** Host-provided unrestricted outbound HTTP access for one plugin binding. */
fun interface PluginHttpClient {
    fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse>
}
