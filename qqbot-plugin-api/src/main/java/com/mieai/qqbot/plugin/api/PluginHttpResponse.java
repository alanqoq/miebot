package com.mieai.qqbot.plugin.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Bounded HTTP response returned without exposing the host HTTP client implementation. */
public record PluginHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    public PluginHttpResponse {
        if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("statusCode is invalid");
        headers = Objects.requireNonNull(headers, "headers must not be null").entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(Objects.requireNonNull(
                                entry.getValue(), "header values must not be null"))));
        body = Objects.requireNonNull(body, "body must not be null").clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String bodyAsUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
