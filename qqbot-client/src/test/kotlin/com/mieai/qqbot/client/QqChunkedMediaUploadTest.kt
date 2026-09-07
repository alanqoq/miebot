package com.mieai.qqbot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.QqAppId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QqChunkedMediaUploadTest {
    @Test
    fun cancellationStopsBeforeFinish() {
        TestHttpServer().use { server ->
            val started = CountDownLatch(1)
            val later = mutableListOf<String>()
            server.handle("/v2/users/u/upload_prepare") { exchange ->
                TestHttpServer.respond(exchange, 200, "{\"upload_id\":\"up\",\"block_size\":\"3\",\"parts\":[{\"index\":0,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/slow")}\"}],\"upload_config\":{\"concurrency\":1,\"retry_timeout\":1,\"retry_delay\":1}}")
            }
            server.handle("/slow") { exchange ->
                started.countDown()
                try { Thread.sleep(5000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                exchange.close()
            }
            server.handle("/v2/users/u/upload_part_finish") { exchange -> later += "finish"; exchange.close() }
            server.handle("/v2/users/u/files") { exchange -> later += "merge"; exchange.close() }
            server.handle("/v2/users/u/messages") { exchange -> later += "message"; exchange.close() }
            val client = QqOpenApiClient(QqClientOptions(openApiBaseUri = server.uri("/"), requestTimeout = Duration.ofSeconds(10)), TokenProvider { CompletableFuture.completedFuture(AccessToken.of("s", Instant.now())) })
            val future = client.sendMedia(QqMediaMessageRequest(QqMessageTargetType.C2C, "u", QqMediaKind.FILE, URI.create("https://cdn.example/f"), null, null, null, 1), ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue
            future.toCompletableFuture().cancel(true)
            Thread.sleep(300)
            assertThat(later).isEmpty()
        }
    }

    @Test
    fun uploadsPartsByIndexWithoutAuthorizationOnPresignedPut() {
        TestHttpServer().use { server ->
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val mapper = ObjectMapper()
            server.handle("/v2/users/u/upload_prepare") { exchange ->
                requests += exchange.requestURI.path
                val body = mapper.readTree(TestHttpServer.readBody(exchange))
                assertThat(body.path("file_size").isTextual).isTrue()
                assertThat(body.path("file_size").asText()).isEqualTo("6")
                assertThat(body.path("md5").asText()).isEqualTo(md5("abcdef".toByteArray()))
                assertThat(body.path("sha1").asText()).hasSize(40)
                assertThat(body.path("md5_10m").asText()).isEqualTo(md5("abcdef".toByteArray()))
                TestHttpServer.respond(exchange, 200, "{\"upload_id\":\"up-1\",\"block_size\":\"3\",\"parts\":[" +
                    "{\"index\":1,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/part-1")}\"}," +
                    "{\"index\":0,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/part-0")}\"}],\"upload_config\":{\"concurrency\":2,\"retry_timeout\":0,\"retry_delay\":0}}")
            }
            server.handle("/part-0") { exchange ->
                requests += "part0:" + exchange.requestHeaders.getFirst("Authorization") + ":" +
                    exchange.requestHeaders.getFirst("X-Union-Appid") + ":" +
                    String(exchange.requestBody.readAllBytes())
                exchange.sendResponseHeaders(200, 0); exchange.close()
            }
            server.handle("/part-1") { exchange ->
                requests += "part1:" + exchange.requestHeaders.getFirst("Authorization") + ":" +
                    exchange.requestHeaders.getFirst("X-Union-Appid") + ":" +
                    String(exchange.requestBody.readAllBytes())
                exchange.sendResponseHeaders(200, 0); exchange.close()
            }
            server.handle("/v2/users/u/upload_part_finish") { exchange ->
                requests += TestHttpServer.readBody(exchange)
                exchange.sendResponseHeaders(200, 0); exchange.close()
            }
            server.handle("/v2/users/u/files") { exchange ->
                requests += exchange.requestURI.path
                TestHttpServer.respond(exchange, 200, "{\"file_info\":\"fi-1\"}")
            }
            server.handle("/v2/users/u/messages") { exchange ->
                requests += TestHttpServer.readBody(exchange)
                TestHttpServer.respond(exchange, 200, "{\"id\":\"m-1\",\"msg_seq\":1}")
            }
            val client = QqOpenApiClient(
                QqClientOptions(openApiBaseUri = server.uri("/"), requestTimeout = Duration.ofSeconds(2)),
                TokenProvider { CompletableFuture.completedFuture(AccessToken.of("secret", Instant.now())) },
                QqAppId.of("app-123"),
            )
            val result = client.sendMedia(
                QqMediaMessageRequest(QqMessageTargetType.C2C, "u", QqMediaKind.FILE, URI.create("https://cdn.example/f"), null, null, null, 1),
                ByteArrayInputStream("abcdef".toByteArray()), 6, "f.bin",
            ).toCompletableFuture().join()
            assertThat(result.id).isEqualTo("m-1")
            assertThat(requests).anyMatch { it.startsWith("part0:null:null:abc") }
            assertThat(requests).anyMatch { it.startsWith("part1:null:null:def") }
            assertThat(requests).anyMatch {
                it.contains("\"upload_id\":\"up-1\"") && it.contains("\"block_size\":\"3\"")
            }
        }
    }

    @Test
    fun sendsUnsignedHashesForHighBitBytes() {
        TestHttpServer().use { server ->
            val bytes = byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x01)
            val mapper = ObjectMapper()
            server.handle("/v2/users/u/upload_prepare") { exchange ->
                val body = mapper.readTree(TestHttpServer.readBody(exchange))
                assertThat(body.path("md5").asText()).isEqualTo("d0a73a08364516679027e7c229cab39c")
                assertThat(body.path("sha1").asText()).isEqualTo("b8a10592a7d2a3d4d81826c2938a1c3c046b3e39")
                assertThat(body.path("md5_10m").asText()).isEqualTo("d0a73a08364516679027e7c229cab39c")
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"upload_id\":\"up\",\"block_size\":\"3\",\"parts\":[" +
                        "{\"index\":0,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/part")}\"}]," +
                        "\"upload_config\":{\"concurrency\":1,\"retry_timeout\":0,\"retry_delay\":0}}",
                )
            }
            server.handle("/part") { exchange ->
                assertThat(exchange.requestBody.readAllBytes().toList()).containsExactlyElementsOf(bytes.toList())
                TestHttpServer.respond(exchange, 200, "")
            }
            server.handle("/v2/users/u/upload_part_finish") { exchange ->
                TestHttpServer.respond(exchange, 200, "")
            }
            server.handle("/v2/users/u/files") { exchange ->
                TestHttpServer.respond(exchange, 200, "{\"file_info\":\"fi\"}")
            }
            server.handle("/v2/users/u/messages") { exchange ->
                TestHttpServer.respond(exchange, 200, "{\"id\":\"m\"}")
            }

            val result = client(server, Duration.ofSeconds(2)).sendMedia(
                request(QqMessageTargetType.C2C, "u"),
                bytes,
            ).toCompletableFuture().join()
            assertThat(result.id).isEqualTo("m")
        }
    }

    @Test
    fun capsChunkedMediaAt200MiB() {
        val client = QqOpenApiClient(QqClientOptions(maxMediaBytes = 256L * 1024 * 1024), TokenProvider { CompletableFuture.completedFuture(AccessToken.of("s", Instant.now())) })
        assertThatThrownBy { client.sendMedia(request(QqMessageTargetType.C2C, "u"), ByteArrayInputStream(byteArrayOf(1)), 200L * 1024 * 1024 + 1).toCompletableFuture().join() }
            .hasRootCauseMessage("media bytes exceed configured limit")
    }

    @Test
    fun keepsExistingConfiguredLimitForChannelAndDirectMedia() {
        val client = QqOpenApiClient(
            QqClientOptions(maxMediaBytes = 256L * 1024 * 1024),
            TokenProvider { CompletableFuture.completedFuture(AccessToken.of("s", Instant.now())) },
        )
        listOf(QqMessageTargetType.CHANNEL, QqMessageTargetType.DIRECT).forEach { targetType ->
            assertThatThrownBy {
                client.sendMedia(
                    request(targetType, "target" ).copy(mediaKind = QqMediaKind.IMAGE),
                    ByteArrayInputStream(ByteArray(0)),
                    200L * 1024 * 1024 + 1,
                ).toCompletableFuture().join()
            }.hasRootCauseMessage("declared media size does not match stream")
        }
    }

    @Test
    fun retriesPresignedPutUntilItSucceedsWithinServerBudget() {
        TestHttpServer().use { server ->
            val attempts = AtomicInteger()
            server.handle("/v2/groups/g/upload_prepare") { exchange ->
                TestHttpServer.respond(exchange, 200, "{\"upload_id\":\"up\",\"block_size\":\"3\",\"parts\":[{\"index\":0,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/retry")}\"}],\"upload_config\":{\"concurrency\":1,\"retry_timeout\":2,\"retry_delay\":0}}")
            }
            server.handle("/retry") { exchange ->
                val attempt = attempts.incrementAndGet()
                TestHttpServer.respond(exchange, if (attempt < 3) 503 else 200, "")
            }
            server.handle("/v2/groups/g/upload_part_finish") { exchange -> TestHttpServer.respond(exchange, 200, "") }
            server.handle("/v2/groups/g/files") { exchange -> TestHttpServer.respond(exchange, 200, "{\"file_info\":\"fi\"}") }
            server.handle("/v2/groups/g/messages") { exchange -> TestHttpServer.respond(exchange, 200, "{\"id\":\"m\"}") }

            val result = client(server, Duration.ofSeconds(2)).sendMedia(
                request(QqMessageTargetType.GROUP, "g"),
                byteArrayOf(1, 2, 3),
            ).toCompletableFuture().join()

            assertThat(result.id).isEqualTo("m")
            assertThat(attempts.get()).isEqualTo(3)
        }
    }

    @Test
    fun retriesPartFinishForTransientBusinessErrorWithinServerBudget() {
        TestHttpServer().use { server ->
            val putAttempts = AtomicInteger()
            val finishAttempts = AtomicInteger()
            val mergeAttempts = AtomicInteger()
            val messageAttempts = AtomicInteger()
            val finishBodies = Collections.synchronizedList(mutableListOf<String>())
            server.handle("/v2/groups/g/upload_prepare") { exchange ->
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"upload_id\":\"up\",\"block_size\":\"3\",\"parts\":[" +
                        "{\"index\":0,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/part")}\"}]," +
                        "\"upload_config\":{\"concurrency\":1,\"retry_timeout\":2,\"retry_delay\":0}}",
                )
            }
            server.handle("/part") { exchange ->
                putAttempts.incrementAndGet()
                TestHttpServer.respond(exchange, 200, "")
            }
            server.handle("/v2/groups/g/upload_part_finish") { exchange ->
                finishBodies += TestHttpServer.readBody(exchange)
                val attempt = finishAttempts.incrementAndGet()
                if (attempt < 3) {
                    TestHttpServer.respond(exchange, 400, "{\"code\":40093001,\"message\":\"retry\"}")
                } else {
                    TestHttpServer.respond(exchange, 200, "")
                }
            }
            server.handle("/v2/groups/g/files") { exchange ->
                mergeAttempts.incrementAndGet()
                TestHttpServer.respond(exchange, 200, "{\"file_info\":\"fi\"}")
            }
            server.handle("/v2/groups/g/messages") { exchange ->
                messageAttempts.incrementAndGet()
                TestHttpServer.respond(exchange, 200, "{\"id\":\"m\"}")
            }

            val result = client(server, Duration.ofSeconds(2)).sendMedia(
                request(QqMessageTargetType.GROUP, "g"),
                byteArrayOf(1, 2, 3),
            ).toCompletableFuture().join()

            assertThat(result.id).isEqualTo("m")
            assertThat(putAttempts.get()).isEqualTo(1)
            assertThat(finishAttempts.get()).isEqualTo(3)
            assertThat(finishBodies).allMatch {
                it.contains("\"upload_id\":\"up\"") &&
                    it.contains("\"part_index\":0") &&
                    it.contains("\"block_size\":\"3\"") &&
                    it.contains("\"md5\":\"5289df737df57326fcdd22597afb1fac\"")
            }
            assertThat(mergeAttempts.get()).isEqualTo(1)
            assertThat(messageAttempts.get()).isEqualTo(1)
        }
    }

    @Test
    fun doesNotRetryPartFinishAfterRetryDelayExceedsBudget() {
        TestHttpServer().use { server ->
            val finishAttempts = AtomicInteger()
            server.handle("/v2/groups/g/upload_prepare") { exchange ->
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"upload_id\":\"up\",\"block_size\":\"3\",\"parts\":[" +
                        "{\"index\":0,\"block_size\":\"3\",\"presigned_url\":\"${server.uri("/part")}\"}]," +
                        "\"upload_config\":{\"concurrency\":1,\"retry_timeout\":1,\"retry_delay\":2}}",
                )
            }
            server.handle("/part") { exchange -> TestHttpServer.respond(exchange, 200, "") }
            server.handle("/v2/groups/g/upload_part_finish") { exchange ->
                finishAttempts.incrementAndGet()
                TestHttpServer.respond(exchange, 400, "{\"code\":40093001,\"message\":\"retry\"}")
            }

            assertThatThrownBy {
                client(server, Duration.ofSeconds(2)).sendMedia(
                    request(QqMessageTargetType.GROUP, "g"),
                    byteArrayOf(1, 2, 3),
                ).toCompletableFuture().join()
            }.hasRootCauseInstanceOf(QqClientException::class.java)
            assertThat(finishAttempts.get()).isEqualTo(1)
        }
    }

    @Test
    fun rejectsPreparePlanThatDoesNotCoverDeclaredMediaSize() {
        TestHttpServer().use { server ->
            server.handle("/v2/users/u/upload_prepare") { exchange ->
                TestHttpServer.respond(exchange, 200, "{\"upload_id\":\"up\",\"block_size\":\"3\",\"parts\":[{\"index\":0,\"block_size\":\"2\",\"presigned_url\":\"${server.uri("/part")}\"}],\"upload_config\":{\"concurrency\":1,\"retry_timeout\":0,\"retry_delay\":0}}")
            }

            assertThatThrownBy {
                client(server, Duration.ofSeconds(2)).sendMedia(
                    request(QqMessageTargetType.C2C, "u"),
                    byteArrayOf(1, 2, 3),
                ).toCompletableFuture().join()
            }.hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseMessage("parts do not cover the declared media size")
        }
    }

    private fun client(server: TestHttpServer, timeout: Duration) = QqOpenApiClient(
        QqClientOptions(openApiBaseUri = server.uri("/"), requestTimeout = timeout),
        TokenProvider { CompletableFuture.completedFuture(AccessToken.of("secret", Instant.now())) },
    )

    private fun request(type: QqMessageTargetType, id: String) = QqMediaMessageRequest(
        type,
        id,
        QqMediaKind.FILE,
        URI.create("https://cdn.example/f"),
        null,
        null,
        null,
        1,
    )

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
