package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.plugin.api.PluginHttpResponse;
import com.mieai.qqbot.plugin.api.PluginHttpRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RestrictedPluginHttpClientTest {
    @Test
    void allowsPrivateHttpSensitiveHeadersRedirectsAndLargeBodies() throws Exception {
        byte[] requestBody = new byte[1024 * 1024 + 1];
        byte[] responseBody = new byte[2 * 1024 * 1024 + 1];
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> cookie = new AtomicReference<>();
        AtomicInteger receivedBytes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            receivedBytes.set(exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(200, responseBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        });
        server.start();
        try (RestrictedPluginHttpClient client = new RestrictedPluginHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            PluginHttpRequest request = new PluginHttpRequest("POST", uri,
                    Map.of("Authorization", "Bearer secret", "Cookie", "session=value"),
                    Optional.of(requestBody), Duration.ofMinutes(2));

            PluginHttpResponse response = client.send(request).toCompletableFuture().join();

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).hasSize(responseBody.length);
            assertThat(authorization).hasValue("Bearer secret");
            assertThat(cookie).hasValue("session=value");
            assertThat(receivedBytes).hasValue(requestBody.length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRequestsAfterTheBindingClientIsClosed() {
        RestrictedPluginHttpClient client = new RestrictedPluginHttpClient();
        client.close();

        assertThatThrownBy(() -> client.send(
                PluginHttpRequest.get(URI.create("http://127.0.0.1/")))
                .toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
