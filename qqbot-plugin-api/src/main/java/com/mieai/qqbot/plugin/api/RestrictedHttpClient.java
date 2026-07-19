package com.mieai.qqbot.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Host-controlled outbound HTTP access without credentials, redirects or private-network targets. */
@FunctionalInterface
public interface RestrictedHttpClient {
    CompletionStage<PluginHttpResponse> send(PluginHttpRequest request);

    static RestrictedHttpClient denied() {
        return request -> CompletableFuture.failedFuture(
                new SecurityException("Plugin HTTP capability is not granted"));
    }
}
