package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.PluginHttpRequest;
import com.mieai.qqbot.plugin.api.PluginHttpResponse;
import com.mieai.qqbot.plugin.api.RestrictedHttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Recording HTTP fake with a caller-provided deterministic responder. */
public final class FakeRestrictedHttpClient implements RestrictedHttpClient {
    private final List<PluginHttpRequest> requests = new ArrayList<>();
    private Function<PluginHttpRequest, PluginHttpResponse> responder;

    public FakeRestrictedHttpClient(PluginHttpResponse response) {
        this(request -> response);
    }

    public FakeRestrictedHttpClient(Function<PluginHttpRequest, PluginHttpResponse> responder) {
        this.responder = Objects.requireNonNull(responder, "responder must not be null");
    }

    @Override
    public synchronized CompletionStage<PluginHttpResponse> send(PluginHttpRequest request) {
        requests.add(Objects.requireNonNull(request, "request must not be null"));
        try {
            return CompletableFuture.completedFuture(responder.apply(request));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public synchronized List<PluginHttpRequest> requests() { return List.copyOf(requests); }
}
