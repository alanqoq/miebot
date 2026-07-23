package com.mieai.qqbot.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Host-provided outbound HTTP access. The historical name is retained for plugin ABI compatibility. */
@FunctionalInterface
public interface RestrictedHttpClient {
    CompletionStage<PluginHttpResponse> send(PluginHttpRequest request);

    static RestrictedHttpClient denied() {
        return request -> CompletableFuture.failedFuture(
                new SecurityException("Plugin HTTP capability is not granted"));
    }
}
