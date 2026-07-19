package com.mieai.qqbot.plugin.api;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Restricted outbound HTTP request. The host enforces URL, header, timeout and size policy. */
public record PluginHttpRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        Optional<byte[]> body,
        Duration timeout) {
    public PluginHttpRequest {
        if (method == null || !(method.equals("GET") || method.equals("POST")
                || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE"))) {
            throw new IllegalArgumentException("method is not supported");
        }
        Objects.requireNonNull(uri, "uri must not be null");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
        body = Objects.requireNonNull(body, "body must not be null")
                .map(value -> value.clone());
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 nanosecond and 30 seconds");
        }
        body.ifPresent(value -> {
            if (value.length > 1024 * 1024) throw new IllegalArgumentException("body exceeds 1 MiB");
        });
    }

    @Override
    public Optional<byte[]> body() {
        return body.map(value -> value.clone());
    }

    public static PluginHttpRequest get(URI uri) {
        return new PluginHttpRequest("GET", uri, Map.of(), Optional.empty(), Duration.ofSeconds(10));
    }
}
