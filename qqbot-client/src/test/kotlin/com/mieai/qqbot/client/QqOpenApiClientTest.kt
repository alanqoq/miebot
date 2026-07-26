package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.BotEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class QqOpenApiClientTest {
    @Test
    fun selectsTheOfficialOpenApiBaseForEachBotEnvironment() {
        assertThat(QqClientOptions.forEnvironment(BotEnvironment.PRODUCTION).openApiBaseUri)
            .isEqualTo(QqClientOptions.DEFAULT_OPEN_API_BASE_URI)
        assertThat(QqClientOptions.forEnvironment(BotEnvironment.SANDBOX).openApiBaseUri)
            .isEqualTo(QqClientOptions.DEFAULT_SANDBOX_OPEN_API_BASE_URI)
    }

    @Test
    fun rejectsMediaLimitsOutsideTheSupportedBotConfigurationRange() {
        assertThatThrownBy { QqClientOptions(maxMediaBytes = 1024L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("1 and 256 MiB")
        assertThatThrownBy { QqClientOptions(maxMediaBytes = 257L * 1024L * 1024L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("1 and 256 MiB")
    }

    @Test
    fun getsGatewayShardLimitsAndCurrentBotWithoutLeakingProtocolDtos() {
        val authorizationHeaders = CopyOnWriteArrayList<String>()
        TestHttpServer().use { server ->
            server.handle("/gateway") { exchange ->
                authorizationHeaders += exchange.requestHeaders.getFirst("Authorization")
                TestHttpServer.respond(exchange, 200, "{\"url\":\"wss://gateway.example/websocket/\",\"future\":true}")
            }
            server.handle("/gateway/bot") { exchange ->
                authorizationHeaders += exchange.requestHeaders.getFirst("Authorization")
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"url\":\"wss://gateway.example/websocket\",\"shards\":4," +
                        "\"session_start_limit\":{\"total\":1000,\"remaining\":999," +
                        "\"reset_after\":86400000,\"max_concurrency\":2,\"future_nested\":1}," +
                        "\"future\":true}",
                )
            }
            server.handle("/users/@me") { exchange ->
                authorizationHeaders += exchange.requestHeaders.getFirst("Authorization")
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"id\":\"6158788878435714165\",\"username\":\"Support Bot\"," +
                        "\"avatar\":\"https://q.qlogo.cn/avatar.png\",\"bot\":true," +
                        "\"future\":{\"nested\":true}}",
                )
            }
            val client = client(server, Duration.ofSeconds(2))

            val gateway = client.getGateway().toCompletableFuture().join()
            val gatewayBot = client.getGatewayBot().toCompletableFuture().join()
            val profile = client.getCurrentBot().toCompletableFuture().join()

            assertThat(gateway).isEqualTo(URI.create("wss://gateway.example/websocket/"))
            assertThat(gatewayBot.url).isEqualTo(URI.create("wss://gateway.example/websocket"))
            assertThat(gatewayBot.recommendedShardCount).isEqualTo(4)
            assertThat(gatewayBot.sessionStartLimit)
                .isEqualTo(SessionStartLimit(1000, 999, Duration.ofDays(1), 2))
            assertThat(profile.platformUserId).isEqualTo("6158788878435714165")
            assertThat(profile.displayName).isEqualTo("Support Bot")
            assertThat(profile.avatarUrl).isEqualTo(URI.create("https://q.qlogo.cn/avatar.png"))
            assertThat(authorizationHeaders).containsExactly(
                "QQBot $ACCESS_TOKEN",
                "QQBot $ACCESS_TOKEN",
                "QQBot $ACCESS_TOKEN",
            )
        }
    }

    @Test
    fun classifiesHttpErrorsAndRetainsStructuredQqDetails() {
        TestHttpServer().use { server ->
            server.handle("/gateway") { exchange ->
                TestHttpServer.respond(
                    exchange,
                    401,
                    "{\"code\":11241,\"message\":\"invalid token\"," +
                        "\"trace_id\":\"trace-123\",\"future\":true}",
                )
            }
            val exception = failureOf(client(server, Duration.ofSeconds(2)).getGateway())

            assertThat(exception.failure).isEqualTo(QqClientFailure.HTTP_STATUS)
            assertThat(exception.httpStatus).isEqualTo(401)
            assertThat(exception.qqCode).isEqualTo(11241)
            assertThat(exception.qqMessage).isEqualTo("invalid token")
            assertThat(exception.qqTraceId).isEqualTo("trace-123")
            assertThat(exception.toString()).doesNotContain(ACCESS_TOKEN)
        }
    }

    @Test
    fun acceptsTheCurrentErrCodeErrorField() {
        TestHttpServer().use { server ->
            server.handle("/gateway") { exchange ->
                TestHttpServer.respond(
                    exchange,
                    403,
                    "{\"err_code\":304003,\"message\":\"missing permission\"," +
                        "\"trace_id\":\"trace-current\"}",
                )
            }
            val exception = failureOf(client(server, Duration.ofSeconds(2)).getGateway())

            assertThat(exception.qqCode).isEqualTo(304003)
            assertThat(exception.qqTraceId).isEqualTo("trace-current")
        }
    }

    @Test
    fun sendsAuthorizedC2cTextReplyWithMessageDedupFields() {
        val method = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val body = AtomicReference<String>()
        TestHttpServer().use { server ->
            server.handle("/v2/users/user-1/messages") { exchange ->
                method.set(exchange.requestMethod)
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                body.set(TestHttpServer.readBody(exchange))
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"id\":\"sent-1\",\"msg_seq\":2,\"timestamp\":\"2026-07-19T00:00:00Z\"}",
                )
            }

            val result = client(server, Duration.ofSeconds(2)).sendText(
                QqTextMessageRequest(
                    QqMessageTargetType.C2C,
                    "user-1",
                    "hello",
                    "message-1",
                    "event-1",
                    2,
                ),
            ).toCompletableFuture().join()

            assertThat(method).hasValue("POST")
            assertThat(authorization).hasValue("QQBot $ACCESS_TOKEN")
            assertThat(body.get())
                .contains("\"content\":\"hello\"")
                .contains("\"msg_id\":\"message-1\"")
                .contains("\"event_id\":\"event-1\"")
                .contains("\"msg_seq\":2")
                .contains("\"msg_type\":0")
            assertThat(result.id).isEqualTo("sent-1")
            assertThat(result.msgSeq).isEqualTo(2)
        }
    }

    @Test
    fun sendsKeyboardAsAnOfficialMarkdownAttachment() {
        val body = AtomicReference<String>()
        TestHttpServer().use { server ->
            server.handle("/v2/users/user-1/messages") { exchange ->
                body.set(TestHttpServer.readBody(exchange))
                TestHttpServer.respond(exchange, 200, "{\"id\":\"keyboard-1\",\"msg_seq\":1}")
            }
            val payload: Map<String, Any> = mapOf(
                "markdown" to mapOf("content" to "Choose an action"),
                "keyboard" to mapOf("id" to "keyboard-template"),
            )

            client(server, Duration.ofSeconds(2)).sendRich(
                QqRichMessageRequest(
                    QqMessageTargetType.C2C,
                    "user-1",
                    QqRichMessageKind.KEYBOARD,
                    payload,
                    null,
                    null,
                    1,
                ),
            ).toCompletableFuture().join()

            assertThat(body.get())
                .contains("\"msg_type\":2")
                .contains("\"markdown\"")
                .contains("Choose an action")
                .contains("\"keyboard\"")
                .doesNotContain("\"msg_type\":8")
        }
    }

    @Test
    fun rejectsAStandaloneKeyboardWithoutMarkdown() {
        assertThatThrownBy {
            QqRichMessageRequest(
                QqMessageTargetType.C2C,
                "user-1",
                QqRichMessageKind.KEYBOARD,
                mapOf("keyboard" to mapOf("id" to "template")),
                null,
                null,
                1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("markdown and keyboard")
    }

    @Test
    fun uploadsAndSendsC2cMediaWithOfficialFileInfoEnvelope() {
        val uploadBody = AtomicReference<String>()
        val messageBody = AtomicReference<String>()
        TestHttpServer().use { server ->
            server.handle("/v2/users/user-1/files") { exchange ->
                uploadBody.set(TestHttpServer.readBody(exchange))
                TestHttpServer.respond(exchange, 200, "{\"file_info\":\"signed-file-info\",\"ttl\":60}")
            }
            server.handle("/v2/users/user-1/messages") { exchange ->
                messageBody.set(TestHttpServer.readBody(exchange))
                TestHttpServer.respond(exchange, 200, "{\"id\":\"media-1\",\"msg_seq\":3}")
            }

            val result = client(server, Duration.ofSeconds(2)).sendMedia(
                QqMediaMessageRequest(
                    QqMessageTargetType.C2C,
                    "user-1",
                    QqMediaKind.IMAGE,
                    URI.create("https://cdn.example/image.png"),
                    "caption",
                    null,
                    null,
                    3,
                ),
            ).toCompletableFuture().join()

            assertThat(uploadBody).hasValueSatisfying { value ->
                assertThat(value)
                    .contains("\"file_type\":1")
                    .contains("https://cdn.example/image.png")
                    .contains("\"srv_send_msg\":false")
            }
            assertThat(messageBody).hasValueSatisfying { value ->
                assertThat(value)
                    .contains("signed-file-info")
                    .contains("\"msg_type\":7")
                    .contains("\"msg_seq\":3")
                    .contains("caption")
            }
            assertThat(result.id).isEqualTo("media-1")
        }
    }

    @Test
    fun uploadsLocalChannelImageAsMultipartFileImage() {
        val contentType = AtomicReference<String>()
        val body = AtomicReference<ByteArray>()
        TestHttpServer().use { server ->
            server.handle("/channels/channel-1/messages") { exchange ->
                contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
                body.set(exchange.requestBody.readAllBytes())
                TestHttpServer.respond(exchange, 200, "{\"id\":\"channel-media-1\",\"msg_seq\":4}")
            }

            val result = client(server, Duration.ofSeconds(2)).sendMedia(
                QqMediaMessageRequest(
                    QqMessageTargetType.CHANNEL,
                    "channel-1",
                    QqMediaKind.IMAGE,
                    URI.create("https://cdn.example/image.png"),
                    "caption",
                    "reply-1",
                    null,
                    4,
                ),
                byteArrayOf(1, 2, 3, 4),
            ).toCompletableFuture().join()

            assertThat(result.id).isEqualTo("channel-media-1")
            assertThat(contentType).hasValueSatisfying { value ->
                assertThat(value).startsWith("multipart/form-data; boundary=")
            }
            assertThat(String(body.get(), StandardCharsets.UTF_8))
                .contains("name=\"file_image\"")
                .contains("filename=\"qqbot-image.bin\"")
                .contains("name=\"content\"")
                .contains("name=\"msg_id\"")
                .contains("reply-1")
                .contains("name=\"msg_seq\"")
            assertThat(body.get()).containsSequence(1, 2, 3, 4)
        }
    }

    @Test
    fun rejectsUnsupportedChannelMediaBeforeMakingARequest() {
        TestHttpServer().use { server ->
            val result = client(server, Duration.ofSeconds(2)).sendMedia(
                QqMediaMessageRequest(
                    QqMessageTargetType.CHANNEL,
                    "channel-1",
                    QqMediaKind.VIDEO,
                    URI.create("https://cdn.example/video.mp4"),
                    null,
                    null,
                    null,
                    1,
                ),
            )
            assertThatThrownBy { result.toCompletableFuture().join() }
                .isInstanceOf(CompletionException::class.java)
                .hasCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun classifiesMalformedSuccessPayloadAsProtocolFailure() {
        TestHttpServer().use { server ->
            server.handle("/gateway") { exchange -> TestHttpServer.respond(exchange, 200, "{not-json") }
            val exception = failureOf(client(server, Duration.ofSeconds(2)).getGateway())
            assertThat(exception.failure).isEqualTo(QqClientFailure.PROTOCOL)
            assertThat(exception.httpStatus).isNull()
        }
    }

    @Test
    fun classifiesRequestTimeoutsSeparatelyFromTransportErrors() {
        TestHttpServer().use { server ->
            server.handle("/gateway") { exchange ->
                try {
                    Thread.sleep(Duration.ofMillis(500))
                    TestHttpServer.respond(exchange, 200, "{\"url\":\"wss://gateway.example/websocket\"}")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val exception = failureOf(client(server, Duration.ofMillis(75)).getGateway())
            assertThat(exception.failure).isEqualTo(QqClientFailure.TIMEOUT)
            assertThat(exception.httpStatus).isNull()
        }
    }

    @Test
    fun classifiesAbruptDisconnectAsTransportFailure() {
        TestHttpServer().use { server ->
            server.handle("/gateway") { exchange -> exchange.close() }
            val exception = failureOf(client(server, Duration.ofSeconds(2)).getGateway())
            assertThat(exception.failure).isEqualTo(QqClientFailure.TRANSPORT)
            assertThat(exception.httpStatus).isNull()
        }
    }

    private fun client(server: TestHttpServer, timeout: Duration): QqOpenApiClient {
        val options = QqClientOptions(
            tokenEndpoint = server.uri("/token"),
            openApiBaseUri = server.uri("/"),
            requestTimeout = timeout,
        )
        val tokenProvider = TokenProvider {
            CompletableFuture.completedFuture(
                AccessToken.of(ACCESS_TOKEN, Instant.parse("2026-07-16T14:00:00Z")),
            )
        }
        return QqOpenApiClient(options, tokenProvider)
    }

    private fun failureOf(stage: CompletionStage<*>): QqClientException {
        try {
            stage.toCompletableFuture().join()
            throw AssertionError("expected request to fail")
        } catch (exception: CompletionException) {
            var cause: Throwable = exception.cause ?: exception
            while (cause is CompletionException && cause.cause != null) {
                cause = cause.cause!!
            }
            assertThat(cause).isInstanceOf(QqClientException::class.java)
            return cause as QqClientException
        }
    }

    companion object {
        private const val ACCESS_TOKEN = "access-token-secret"
    }
}
