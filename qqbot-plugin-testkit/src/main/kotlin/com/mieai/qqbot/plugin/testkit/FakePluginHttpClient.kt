package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Recording HTTP fake with a caller-provided deterministic responder. */
class FakePluginHttpClient(
    private val responder: (PluginHttpRequest) -> PluginHttpResponse,
) : PluginHttpClient {
    private val recordedRequests = mutableListOf<PluginHttpRequest>()

    @Synchronized
    override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> {
        recordedRequests.add(request)
        return try {
            CompletableFuture.completedFuture(responder(request))
        } catch (exception: RuntimeException) {
            CompletableFuture.failedFuture(exception)
        }
    }

    @Synchronized
    fun requests(): List<PluginHttpRequest> = recordedRequests.toList()
}
