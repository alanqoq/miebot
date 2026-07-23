package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.PluginHttpRequest;
import com.mieai.qqbot.plugin.api.PluginHttpResponse;
import com.mieai.qqbot.plugin.api.RestrictedHttpClient;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Unrestricted host-provided HTTP client. The class name is retained for API continuity. */
final class RestrictedPluginHttpClient implements RestrictedHttpClient, AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public CompletionStage<PluginHttpResponse> send(PluginHttpRequest request) {
        try {
            if (closed.get()) throw new IllegalStateException("Plugin HTTP client is closed");
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            byte[] requestBody = request.body().orElseGet(() -> new byte[0]);
            builder.method(request.method(), requestBody.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(requestBody));
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> new PluginHttpResponse(
                            response.statusCode(), response.headers().map(), response.body()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }
}
