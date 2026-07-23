package com.mieai.qqbot.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.protocol.json.JsonCodecs;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkQqHttpTransportTest {
    private static final AccessToken TOKEN = AccessToken.of(
            "transport-token", Instant.parse("2026-07-22T12:00:00Z"));

    @Test
    void supportsAuthorizedPutPatchAndDeleteIncludingEmptySuccessResponses() throws Exception {
        AtomicReference<String> putBody = new AtomicReference<>();
        AtomicReference<String> patchBody = new AtomicReference<>();
        AtomicReference<String> callbackAppId = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/put", exchange -> {
                assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
                putBody.set(TestHttpServer.readBody(exchange));
                callbackAppId.set(exchange.getRequestHeaders().getFirst("X-Callback-AppID"));
                exchange.sendResponseHeaders(204, -1L);
                exchange.close();
            });
            server.handle("/patch", exchange -> {
                assertThat(exchange.getRequestMethod()).isEqualTo("PATCH");
                patchBody.set(TestHttpServer.readBody(exchange));
                TestHttpServer.respond(exchange, 200, "{\"value\":\"updated\"}");
            });
            server.handle("/delete", exchange -> {
                assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
                assertThat(TestHttpServer.readBody(exchange)).isEmpty();
                exchange.sendResponseHeaders(204, -1L);
                exchange.close();
            });
            JdkQqHttpTransport transport = transport();

            transport.putAuthorizedJson(server.uri("/put"), TOKEN, Map.of("code", 0),
                    Map.of("X-Callback-AppID", "app-1"), Void.class).toCompletableFuture().join();
            ValueResponse response = transport.patchAuthorizedJson(
                    server.uri("/patch"), TOKEN, Map.of("name", "new-name"), ValueResponse.class)
                    .toCompletableFuture().join();
            transport.deleteAuthorized(server.uri("/delete"), TOKEN, Void.class)
                    .toCompletableFuture().join();

            assertThat(putBody).hasValue("{\"code\":0}");
            assertThat(patchBody).hasValue("{\"name\":\"new-name\"}");
            assertThat(callbackAppId).hasValue("app-1");
            assertThat(response.value()).isEqualTo("updated");
        }
    }

    @Test
    void rejectsAnEmptyResponseWhenTheEndpointPromisesJson() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/empty", exchange -> {
                exchange.sendResponseHeaders(204, -1L);
                exchange.close();
            });

            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> transport().getJson(server.uri("/empty"), TOKEN, ValueResponse.class)
                            .toCompletableFuture().join());

            assertThat(failure).isNotNull();
            assertThat(failure).isInstanceOf(CompletionException.class);
            assertThat(failure.getCause()).isInstanceOf(QqClientException.class);
            assertThat(((QqClientException) failure.getCause()).failure())
                    .isEqualTo(QqClientFailure.PROTOCOL);
        }
    }

    private static JdkQqHttpTransport transport() {
        return new JdkQqHttpTransport(JsonCodecs.defaultCodec(), Duration.ofSeconds(2));
    }

    private record ValueResponse(String value) {}
}
