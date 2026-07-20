package com.mieai.qqbot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotProfile;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class QqOpenApiClientTest {
    @Test
    void selectsTheOfficialOpenApiBaseForEachBotEnvironment() {
        assertThat(QqClientOptions.defaults(BotEnvironment.PRODUCTION).openApiBaseUri())
                .isEqualTo(QqClientOptions.DEFAULT_OPEN_API_BASE_URI);
        assertThat(QqClientOptions.defaults(BotEnvironment.SANDBOX).openApiBaseUri())
                .isEqualTo(QqClientOptions.DEFAULT_SANDBOX_OPEN_API_BASE_URI);
    }

    @Test
    void rejectsMediaLimitsOutsideTheSupportedBotConfigurationRange() {
        assertThatThrownBy(() -> QqClientOptions.builder().maxMediaBytes(1024L).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 256 MiB");
        assertThatThrownBy(() -> QqClientOptions.builder()
                .maxMediaBytes(257L * 1024L * 1024L).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 256 MiB");
    }

    private static final String ACCESS_TOKEN = "access-token-secret";

    @Test
    void getsGatewayShardLimitsAndCurrentBotWithoutLeakingProtocolDtos() throws Exception {
        List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/gateway", exchange -> {
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                TestHttpServer.respond(
                        exchange,
                        200,
                        "{\"url\":\"wss://gateway.example/websocket/\",\"future\":true}");
            });
            server.handle("/gateway/bot", exchange -> {
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                TestHttpServer.respond(
                        exchange,
                        200,
                        "{\"url\":\"wss://gateway.example/websocket\",\"shards\":4,"
                                + "\"session_start_limit\":{\"total\":1000,\"remaining\":999,"
                                + "\"reset_after\":86400000,\"max_concurrency\":2,\"future_nested\":1},"
                                + "\"future\":true}");
            });
            server.handle("/users/@me", exchange -> {
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                TestHttpServer.respond(
                        exchange,
                        200,
                        "{\"id\":\"6158788878435714165\",\"username\":\"Support Bot\","
                                + "\"avatar\":\"https://q.qlogo.cn/avatar.png\",\"bot\":true,"
                                + "\"future\":{\"nested\":true}}");
            });
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            URI gateway = client.getGateway().toCompletableFuture().join();
            GatewayBotInfo gatewayBot = client.getGatewayBot().toCompletableFuture().join();
            BotProfile profile = client.getCurrentBot().toCompletableFuture().join();

            assertThat(gateway).isEqualTo(URI.create("wss://gateway.example/websocket/"));
            assertThat(gatewayBot.url()).isEqualTo(URI.create("wss://gateway.example/websocket"));
            assertThat(gatewayBot.recommendedShardCount()).isEqualTo(4);
            assertThat(gatewayBot.sessionStartLimit())
                    .isEqualTo(new SessionStartLimit(1000, 999, Duration.ofDays(1), 2));
            assertThat(profile.platformUserId()).isEqualTo("6158788878435714165");
            assertThat(profile.displayName()).isEqualTo("Support Bot");
            assertThat(profile.avatarUrl()).contains(URI.create("https://q.qlogo.cn/avatar.png"));
            assertThat(authorizationHeaders).containsExactly(
                    "QQBot " + ACCESS_TOKEN,
                    "QQBot " + ACCESS_TOKEN,
                    "QQBot " + ACCESS_TOKEN);
        }
    }

    @Test
    void classifiesHttpErrorsAndRetainsStructuredQqDetails() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/gateway", exchange -> TestHttpServer.respond(
                    exchange,
                    401,
                    "{\"code\":11241,\"message\":\"invalid token\","
                            + "\"trace_id\":\"trace-123\",\"future\":true}"));
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            QqClientException exception = failureOf(client.getGateway());

            assertThat(exception.failure()).isEqualTo(QqClientFailure.HTTP_STATUS);
            assertThat(exception.httpStatus()).hasValue(401);
            assertThat(exception.qqCode()).hasValue(11241);
            assertThat(exception.qqMessage()).contains("invalid token");
            assertThat(exception.qqTraceId()).contains("trace-123");
            assertThat(exception.toString()).doesNotContain(ACCESS_TOKEN);
        }
    }

    @Test
    void sendsAuthorizedC2cTextReplyWithMessageDedupFields() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/v2/users/user-1/messages", exchange -> {
                method.set(exchange.getRequestMethod());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(TestHttpServer.readBody(exchange));
                TestHttpServer.respond(exchange, 200,
                        "{\"id\":\"sent-1\",\"msg_seq\":2,\"timestamp\":\"2026-07-19T00:00:00Z\"}");
            });
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            QqMessageSendResult result = client.sendText(new QqTextMessageRequest(
                    QqMessageTargetType.C2C, "user-1", "hello", Optional.of("message-1"),
                    Optional.of("event-1"), 2)).toCompletableFuture().join();

            assertThat(method).hasValue("POST");
            assertThat(authorization).hasValue("QQBot " + ACCESS_TOKEN);
            assertThat(body.get()).contains("\"content\":\"hello\"")
                    .contains("\"msg_id\":\"message-1\"")
                    .contains("\"event_id\":\"event-1\"")
                    .contains("\"msg_seq\":2")
                    .contains("\"msg_type\":0");
            assertThat(result.id()).isEqualTo("sent-1");
            assertThat(result.msgSeq()).isEqualTo(2);
        }
    }

    @Test
    void sendsKeyboardAsAnOfficialMarkdownAttachment() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/v2/users/user-1/messages", exchange -> {
                body.set(TestHttpServer.readBody(exchange));
                TestHttpServer.respond(exchange, 200, "{\"id\":\"keyboard-1\",\"msg_seq\":1}");
            });
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));
            Map<String, Object> payload = Map.of(
                    "markdown", Map.of("content", "Choose an action"),
                    "keyboard", Map.of("id", "keyboard-template"));

            client.sendRich(new QqRichMessageRequest(QqMessageTargetType.C2C, "user-1",
                    QqRichMessageKind.KEYBOARD, payload, Optional.empty(), Optional.empty(), 1))
                    .toCompletableFuture().join();

            assertThat(body.get()).contains("\"msg_type\":2")
                    .contains("\"markdown\"")
                    .contains("Choose an action")
                    .contains("\"keyboard\"")
                    .doesNotContain("\"msg_type\":8");
        }
    }

    @Test
    void rejectsAStandaloneKeyboardWithoutMarkdown() {
        assertThatThrownBy(() -> new QqRichMessageRequest(QqMessageTargetType.C2C, "user-1",
                QqRichMessageKind.KEYBOARD, Map.of("keyboard", Map.of("id", "template")),
                Optional.empty(), Optional.empty(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("markdown and keyboard");
    }

    @Test
    void uploadsAndSendsC2cMediaWithOfficialFileInfoEnvelope() throws Exception {
        AtomicReference<String> uploadBody = new AtomicReference<>();
        AtomicReference<String> messageBody = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/v2/users/user-1/files", exchange -> {
                uploadBody.set(TestHttpServer.readBody(exchange));
                TestHttpServer.respond(exchange, 200, "{\"file_info\":\"signed-file-info\",\"ttl\":60}");
            });
            server.handle("/v2/users/user-1/messages", exchange -> {
                messageBody.set(TestHttpServer.readBody(exchange));
                TestHttpServer.respond(exchange, 200, "{\"id\":\"media-1\",\"msg_seq\":3}");
            });
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            QqMessageSendResult result = client.sendMedia(new QqMediaMessageRequest(
                    QqMessageTargetType.C2C, "user-1", QqMediaKind.IMAGE,
                    URI.create("https://cdn.example/image.png"), Optional.of("caption"),
                    Optional.empty(), Optional.empty(), 3)).toCompletableFuture().join();

            assertThat(uploadBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("\"file_type\":1")
                    .contains("https://cdn.example/image.png")
                    .contains("\"srv_send_msg\":false"));
            assertThat(messageBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("signed-file-info")
                    .contains("\"msg_type\":7")
                    .contains("\"msg_seq\":3")
                    .contains("caption"));
            assertThat(result.id()).isEqualTo("media-1");
        }
    }

    @Test
    void uploadsLocalChannelImageAsMultipartFileImage() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/channels/channel-1/messages", exchange -> {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                body.set(exchange.getRequestBody().readAllBytes());
                TestHttpServer.respond(exchange, 200, "{\"id\":\"channel-media-1\",\"msg_seq\":4}");
            });
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            QqMessageSendResult result = client.sendMedia(new QqMediaMessageRequest(
                    QqMessageTargetType.CHANNEL, "channel-1", QqMediaKind.IMAGE,
                    URI.create("https://cdn.example/image.png"), Optional.of("caption"),
                    Optional.of("reply-1"), Optional.empty(), 4), new byte[] {1, 2, 3, 4})
                    .toCompletableFuture().join();

            assertThat(result.id()).isEqualTo("channel-media-1");
            assertThat(contentType).hasValueSatisfying(value ->
                    assertThat(value).startsWith("multipart/form-data; boundary="));
            assertThat(new String(body.get(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("name=\"file_image\"")
                    .contains("filename=\"qqbot-image.bin\"")
                    .contains("name=\"content\"")
                    .contains("name=\"msg_id\"")
                    .contains("reply-1")
                    .contains("name=\"msg_seq\"");
            assertThat(body.get()).containsSequence((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        }
    }

    @Test
    void rejectsUnsupportedChannelMediaBeforeMakingARequest() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));
            var result = client.sendMedia(new QqMediaMessageRequest(
                    QqMessageTargetType.CHANNEL, "channel-1", QqMediaKind.VIDEO,
                    URI.create("https://cdn.example/video.mp4"), Optional.empty(),
                    Optional.empty(), Optional.empty(), 1));
            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void classifiesMalformedSuccessPayloadAsProtocolFailure() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/gateway", exchange ->
                    TestHttpServer.respond(exchange, 200, "{not-json"));
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            QqClientException exception = failureOf(client.getGateway());

            assertThat(exception.failure()).isEqualTo(QqClientFailure.PROTOCOL);
            assertThat(exception.httpStatus()).isEmpty();
        }
    }

    @Test
    void classifiesRequestTimeoutsSeparatelyFromTransportErrors() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/gateway", exchange -> {
                try {
                    Thread.sleep(Duration.ofMillis(500));
                    TestHttpServer.respond(
                            exchange, 200, "{\"url\":\"wss://gateway.example/websocket\"}");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            QqOpenApiClient client = client(server, Duration.ofMillis(75));

            QqClientException exception = failureOf(client.getGateway());

            assertThat(exception.failure()).isEqualTo(QqClientFailure.TIMEOUT);
            assertThat(exception.httpStatus()).isEmpty();
        }
    }

    @Test
    void classifiesAbruptDisconnectAsTransportFailure() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/gateway", exchange -> exchange.close());
            QqOpenApiClient client = client(server, Duration.ofSeconds(2));

            QqClientException exception = failureOf(client.getGateway());

            assertThat(exception.failure()).isEqualTo(QqClientFailure.TRANSPORT);
            assertThat(exception.httpStatus()).isEmpty();
        }
    }

    private static QqOpenApiClient client(TestHttpServer server, Duration timeout) {
        QqClientOptions options = QqClientOptions.builder()
                .tokenEndpoint(server.uri("/token"))
                .openApiBaseUri(server.uri("/"))
                .requestTimeout(timeout)
                .build();
        TokenProvider tokenProvider = () -> java.util.concurrent.CompletableFuture.completedFuture(
                AccessToken.of(ACCESS_TOKEN, Instant.parse("2026-07-16T14:00:00Z")));
        return new QqOpenApiClient(options, tokenProvider);
    }

    private static QqClientException failureOf(java.util.concurrent.CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("expected request to fail");
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(QqClientException.class);
            return (QqClientException) cause;
        }
    }
}
