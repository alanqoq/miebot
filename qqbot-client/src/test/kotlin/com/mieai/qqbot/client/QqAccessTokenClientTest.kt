package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.QqAppId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class QqAccessTokenClientTest {
    @Test
    fun destroysBotCredentialsWhenTheirRuntimeStops() {
        val secret = AppSecret.of(charArrayOf('s', 'e', 'c', 'r', 'e', 't'))
        val credentials = BotCredentials(QqAppId.of("1029384756"), secret)

        credentials.close()

        assertThat(secret.isDestroyed).isTrue()
        assertThatIllegalStateException().isThrownBy { secret.rawValue() }
    }

    @Test
    fun requestsTokenAsynchronouslyAndAcceptsStringExpiryWithUnknownFields() {
        val requestBody = AtomicReference<String>()
        TestHttpServer().use { server ->
            server.handle("/token") { exchange ->
                requestBody.set(TestHttpServer.readBody(exchange))
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"access_token\":\"token-secret-value\",\"expires_in\":\"7200\",\"future\":true}",
                )
            }
            val clock = MutableClock(NOW)
            val options = options(server, Duration.ofSeconds(60))
            val client = QqAccessTokenClient(options, clock)

            val pending: CompletionStage<AccessToken> = client.requestToken(CREDENTIALS)
            val token = pending.toCompletableFuture().join()

            assertThat(token.expiresAt).isEqualTo(NOW.plusSeconds(7200))
            assertThat(requestBody.get())
                .contains("\"appId\":\"1029384756\"")
                .contains("\"clientSecret\":\"client-secret-value\"")
            assertThat(token.toString()).doesNotContain("token-secret-value")
            assertThat(CREDENTIALS.toString()).doesNotContain("client-secret-value")
            assertThat(CREDENTIALS.appSecret.toString()).doesNotContain("client-secret-value")
        }
    }

    @Test
    fun coalescesConcurrentRefreshesIntoOneTokenRequest() {
        val requests = AtomicInteger()
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        TestHttpServer().use { server ->
            server.handle("/token") { exchange ->
                requests.incrementAndGet()
                requestStarted.countDown()
                try {
                    check(releaseResponse.await(5, TimeUnit.SECONDS)) { "test did not release token response" }
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException(exception)
                }
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"access_token\":\"shared-token\",\"expires_in\":\"7200\"}",
                )
            }
            val clock = MutableClock(NOW)
            val options = options(server, Duration.ofSeconds(60))
            val requester = QqAccessTokenClient(options, clock)
            val provider: TokenProvider = SingleFlightTokenProvider(
                CREDENTIALS,
                requester,
                options,
                clock,
            )

            val futures = List(32) { provider.getAccessToken().toCompletableFuture() }
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue()
            releaseResponse.countDown()
            CompletableFuture.allOf(*futures.toTypedArray()).join()

            val first = futures.first().join()
            assertThat(requests).hasValue(1)
            futures.forEach { future -> assertThat(future.join()).isSameAs(first) }
        }
    }

    @Test
    fun refreshesAtConfiguredSkewAndAcceptsNumericExpiry() {
        val requests = AtomicInteger()
        TestHttpServer().use { server ->
            server.handle("/token") { exchange ->
                val request = requests.incrementAndGet()
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"access_token\":\"token-$request\",\"expires_in\":120}",
                )
            }
            val clock = MutableClock(NOW)
            val options = options(server, Duration.ofSeconds(60))
            val requester = QqAccessTokenClient(options, clock)
            val provider: TokenProvider = SingleFlightTokenProvider(
                CREDENTIALS,
                requester,
                options,
                clock,
            )

            val initial = provider.getAccessToken().toCompletableFuture().join()
            clock.advance(Duration.ofSeconds(59))
            val cached = provider.getAccessToken().toCompletableFuture().join()
            clock.advance(Duration.ofSeconds(1))
            val refreshed = provider.getAccessToken().toCompletableFuture().join()

            assertThat(cached).isSameAs(initial)
            assertThat(refreshed).isNotSameAs(initial)
            assertThat(refreshed.expiresAt).isEqualTo(NOW.plusSeconds(180))
            assertThat(requests).hasValue(2)
        }
    }

    @Test
    fun refreshesAfterTheCurrentTokenIsInvalidated() {
        val requests = AtomicInteger()
        val requester = AccessTokenRequester {
            val request = requests.incrementAndGet()
            CompletableFuture.completedFuture(AccessToken.of("token-$request", NOW.plusSeconds(7200)))
        }
        val options = QqClientOptions(tokenRefreshSkew = Duration.ofSeconds(60))
        val provider: TokenProvider = SingleFlightTokenProvider(
            CREDENTIALS,
            requester,
            options,
            MutableClock(NOW),
        )

        val first = provider.getAccessToken().toCompletableFuture().join()
        provider.invalidate(first)
        val second = provider.getAccessToken().toCompletableFuture().join()
        provider.invalidate(first)
        val stillSecond = provider.getAccessToken().toCompletableFuture().join()

        assertThat(second).isNotSameAs(first)
        assertThat(stillSecond).isSameAs(second)
        assertThat(requests).hasValue(2)
    }

    @Test
    fun classifiesHttp200BusinessErrorAsSanitizedAuthenticationFailure() {
        TestHttpServer().use { server ->
            server.handle("/token") { exchange ->
                TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"code\":11241,\"message\":\"rejected client-secret-value remote-detail\"}",
                )
            }
            val client = QqAccessTokenClient(options(server, Duration.ofSeconds(60)), MutableClock(NOW))

            val exception = failureOf(client.requestToken(CREDENTIALS))

            assertThat(exception.failure).isEqualTo(QqClientFailure.AUTHENTICATION)
            assertThat(exception.httpStatus).isNull()
            assertThat(exception.qqCode).isEqualTo(11241)
            assertThat(exception.qqMessage).isNull()
            assertThat(exception.toString())
                .doesNotContain("client-secret-value")
                .doesNotContain("remote-detail")
        }
    }

    @Test
    fun treatsMissingTokenWithoutNonzeroBusinessCodeAsProtocolFailure() {
        TestHttpServer().use { server ->
            server.handle("/token") { exchange ->
                TestHttpServer.respond(exchange, 200, "{\"code\":0,\"message\":\"ok\"}")
            }
            val client = QqAccessTokenClient(options(server, Duration.ofSeconds(60)), MutableClock(NOW))

            val exception = failureOf(client.requestToken(CREDENTIALS))

            assertThat(exception.failure).isEqualTo(QqClientFailure.PROTOCOL)
            assertThat(exception.qqCode).isNull()
            assertThat(exception.qqMessage).isNull()
        }
    }

    private fun options(server: TestHttpServer, skew: Duration): QqClientOptions =
        QqClientOptions(
            tokenEndpoint = server.uri("/token"),
            openApiBaseUri = server.uri("/"),
            requestTimeout = Duration.ofSeconds(2),
            tokenRefreshSkew = skew,
        )

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
        private val CREDENTIALS = BotCredentials(
            QqAppId.of("1029384756"),
            AppSecret.of("client-secret-value"),
        )
        private val NOW = Instant.parse("2026-07-16T12:00:00Z")
    }
}
