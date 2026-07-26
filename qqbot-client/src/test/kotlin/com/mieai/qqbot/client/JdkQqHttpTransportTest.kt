package com.mieai.qqbot.client

import com.mieai.qqbot.protocol.json.JsonCodecs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

class JdkQqHttpTransportTest {
    @Test
    fun supportsAuthorizedPutPatchAndDeleteIncludingEmptySuccessResponses() {
        val putBody = AtomicReference<String>()
        val patchBody = AtomicReference<String>()
        val callbackAppId = AtomicReference<String>()
        TestHttpServer().use { server ->
            server.handle("/put") { exchange ->
                assertThat(exchange.requestMethod).isEqualTo("PUT")
                putBody.set(TestHttpServer.readBody(exchange))
                callbackAppId.set(exchange.requestHeaders.getFirst("X-Callback-AppID"))
                exchange.sendResponseHeaders(204, -1L)
                exchange.close()
            }
            server.handle("/patch") { exchange ->
                assertThat(exchange.requestMethod).isEqualTo("PATCH")
                patchBody.set(TestHttpServer.readBody(exchange))
                TestHttpServer.respond(exchange, 200, "{\"value\":\"updated\"}")
            }
            server.handle("/delete") { exchange ->
                assertThat(exchange.requestMethod).isEqualTo("DELETE")
                assertThat(TestHttpServer.readBody(exchange)).isEmpty()
                exchange.sendResponseHeaders(204, -1L)
                exchange.close()
            }

            val transport = transport()
            transport.putAuthorizedJson(
                server.uri("/put"),
                TOKEN,
                mapOf("code" to 0),
                mapOf("X-Callback-AppID" to "app-1"),
                Void::class.java,
            ).toCompletableFuture().join()
            val response = transport.patchAuthorizedJson(
                server.uri("/patch"),
                TOKEN,
                mapOf("name" to "new-name"),
                ValueResponse::class.java,
            ).toCompletableFuture().join()
            transport.deleteAuthorized(server.uri("/delete"), TOKEN, Void::class.java)
                .toCompletableFuture().join()

            assertThat(putBody).hasValue("{\"code\":0}")
            assertThat(patchBody).hasValue("{\"name\":\"new-name\"}")
            assertThat(callbackAppId).hasValue("app-1")
            assertThat(response.value).isEqualTo("updated")
        }
    }

    @Test
    fun rejectsAnEmptyResponseWhenTheEndpointPromisesJson() {
        TestHttpServer().use { server ->
            server.handle("/empty") { exchange ->
                exchange.sendResponseHeaders(204, -1L)
                exchange.close()
            }

            val failure = catchThrowable {
                transport().getJson(server.uri("/empty"), TOKEN, ValueResponse::class.java)
                    .toCompletableFuture().join()
            }

            assertThat(failure).isNotNull().isInstanceOf(CompletionException::class.java)
            assertThat(failure.cause).isInstanceOf(QqClientException::class.java)
            assertThat((failure.cause as QqClientException).failure).isEqualTo(QqClientFailure.PROTOCOL)
        }
    }

    private fun transport() = JdkQqHttpTransport(JsonCodecs.defaultCodec(), Duration.ofSeconds(2))

    private data class ValueResponse(val value: String)

    companion object {
        private val TOKEN = AccessToken.of("transport-token", Instant.parse("2026-07-22T12:00:00Z"))
    }
}
