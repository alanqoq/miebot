package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JdkPluginHttpClientTest {
    @Test
    fun allowsPrivateHttpSensitiveHeadersRedirectsAndLargeBodies() {
        val requestBody = ByteArray(1024 * 1024 + 1)
        val responseBody = ByteArray(2 * 1024 * 1024 + 1)
        val authorization = AtomicReference<String>()
        val cookie = AtomicReference<String>()
        val receivedBytes = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.set("Location", "/target")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            cookie.set(exchange.requestHeaders.getFirst("Cookie"))
            receivedBytes.set(exchange.requestBody.readAllBytes().size)
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()
        try {
            JdkPluginHttpClient().use { client ->
                val uri = URI.create("http://127.0.0.1:${server.address.port}/redirect")
                val request = PluginHttpRequest(
                    "POST",
                    uri,
                    mapOf("Authorization" to "Bearer secret", "Cookie" to "session=value"),
                    requestBody,
                    Duration.ofMinutes(2),
                )

                val response = client.send(request).toCompletableFuture().join()

                assertThat(response.statusCode).isEqualTo(200)
                assertThat(response.body).hasSize(responseBody.size)
                assertThat(authorization).hasValue("Bearer secret")
                assertThat(cookie).hasValue("session=value")
                assertThat(receivedBytes).hasValue(requestBody.size)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsRequestsAfterTheBindingClientIsClosed() {
        val client = JdkPluginHttpClient()
        client.close()

        assertThatThrownBy {
            client.send(PluginHttpRequest.get(URI.create("http://127.0.0.1/")))
                .toCompletableFuture().join()
        }.hasRootCauseInstanceOf(IllegalStateException::class.java)
    }
}
