package com.mieai.qqbot.plugin.api;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Outbound HTTP request forwarded through the host's JDK HTTP client. */
public record PluginHttpRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        Optional<byte[]> body,
        Duration timeout) {
    public PluginHttpRequest {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method must not be blank");
        Objects.requireNonNull(uri, "uri must not be null");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
        body = Objects.requireNonNull(body, "body must not be null")
                .map(value -> value.clone());
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public Optional<byte[]> body() {
        return body.map(value -> value.clone());
    }

    public static PluginHttpRequest get(URI uri) {
        return new PluginHttpRequest("GET", uri, Map.of(), Optional.empty(), Duration.ofSeconds(10));
    }
}
