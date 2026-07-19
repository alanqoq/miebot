package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.plugin.api.PluginHttpRequest;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RestrictedPluginHttpClientTest {
    @Test
    void rejectsPrivateTargetsAndSensitiveHeadersBeforeSending() {
        try (RestrictedPluginHttpClient client = new RestrictedPluginHttpClient()) {
            assertThatThrownBy(() -> client.send(
                    PluginHttpRequest.get(URI.create("https://127.0.0.1/private")))
                    .toCompletableFuture().join())
                    .hasRootCauseInstanceOf(SecurityException.class);

            PluginHttpRequest credential = new PluginHttpRequest("GET", URI.create("https://1.1.1.1/"),
                    Map.of("Authorization", "secret"), Optional.empty(), Duration.ofSeconds(1));
            assertThatThrownBy(() -> client.send(credential).toCompletableFuture().join())
                    .hasRootCauseInstanceOf(SecurityException.class);
        }
    }

    @Test
    void rejectsRequestsAfterTheBindingClientIsClosed() {
        RestrictedPluginHttpClient client = new RestrictedPluginHttpClient();
        client.close();

        assertThatThrownBy(() -> client.send(
                PluginHttpRequest.get(URI.create("https://example.com/")))
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
