package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Unrestricted host-provided HTTP client backed by the JDK HTTP implementation. */
class JdkPluginHttpClient : PluginHttpClient, AutoCloseable {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    private val closed = AtomicBoolean()

    override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> = try {
        check(!closed.get()) { "Plugin HTTP client is closed" }
        val builder = HttpRequest.newBuilder(request.uri).timeout(request.timeout)
        request.headers.forEach(builder::header)
        val requestBody = request.body ?: ByteArray(0)
        builder.method(
            request.method,
            if (requestBody.isEmpty()) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofByteArray(requestBody)
            },
        )
        client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            .thenApply { response ->
                PluginHttpResponse(response.statusCode(), response.headers().map(), response.body())
            }
    } catch (exception: RuntimeException) {
        CompletableFuture.failedFuture(exception)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) client.close()
    }
}
