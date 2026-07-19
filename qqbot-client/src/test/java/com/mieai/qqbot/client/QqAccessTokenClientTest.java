package com.mieai.qqbot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.mieai.qqbot.domain.bot.QqAppId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QqAccessTokenClientTest {
    private static final BotCredentials CREDENTIALS = new BotCredentials(
            QqAppId.of("1029384756"), AppSecret.of("client-secret-value"));
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void destroysBotCredentialsWhenTheirRuntimeStops() {
        AppSecret secret = AppSecret.of(new char[] {'s', 'e', 'c', 'r', 'e', 't'});
        BotCredentials credentials = new BotCredentials(QqAppId.of("1029384756"), secret);

        credentials.close();

        assertThat(secret.isDestroyed()).isTrue();
        assertThatIllegalStateException().isThrownBy(secret::rawValue);
    }

    @Test
    void requestsTokenAsynchronouslyAndAcceptsStringExpiryWithUnknownFields() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token", exchange -> {
                requestBody.set(TestHttpServer.readBody(exchange));
                TestHttpServer.respond(
                        exchange,
                        200,
                        "{\"access_token\":\"token-secret-value\",\"expires_in\":\"7200\",\"future\":true}");
            });
            MutableClock clock = new MutableClock(NOW);
            QqClientOptions options = options(server, Duration.ofSeconds(60));
            QqAccessTokenClient client = new QqAccessTokenClient(options, clock);

            CompletionStage<AccessToken> pending = client.requestToken(CREDENTIALS);
            AccessToken token = pending.toCompletableFuture().join();

            assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(7200));
            assertThat(requestBody.get())
                    .contains("\"appId\":\"1029384756\"")
                    .contains("\"clientSecret\":\"client-secret-value\"");
            assertThat(token.toString()).doesNotContain("token-secret-value");
            assertThat(CREDENTIALS.toString()).doesNotContain("client-secret-value");
            assertThat(CREDENTIALS.appSecret().toString()).doesNotContain("client-secret-value");
        }
    }

    @Test
    void coalescesConcurrentRefreshesIntoOneTokenRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token", exchange -> {
                requests.incrementAndGet();
                requestStarted.countDown();
                try {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release token response");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                TestHttpServer.respond(
                        exchange,
                        200,
                        "{\"access_token\":\"shared-token\",\"expires_in\":\"7200\"}");
            });
            MutableClock clock = new MutableClock(NOW);
            QqClientOptions options = options(server, Duration.ofSeconds(60));
            QqAccessTokenClient requester = new QqAccessTokenClient(options, clock);
            TokenProvider provider = new SingleFlightTokenProvider(
                    CREDENTIALS, requester, options.tokenRefreshSkew(), clock);

            List<CompletableFuture<AccessToken>> futures = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                futures.add(provider.getAccessToken().toCompletableFuture());
            }
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseResponse.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            AccessToken first = futures.getFirst().join();
            assertThat(requests).hasValue(1);
            assertThat(futures).allSatisfy(future -> assertThat(future.join()).isSameAs(first));
        }
    }

    @Test
    void refreshesAtConfiguredSkewAndAcceptsNumericExpiry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token", exchange -> {
                int request = requests.incrementAndGet();
                TestHttpServer.respond(
                        exchange,
                        200,
                        "{\"access_token\":\"token-" + request + "\",\"expires_in\":120}");
            });
            MutableClock clock = new MutableClock(NOW);
            QqClientOptions options = options(server, Duration.ofSeconds(60));
            QqAccessTokenClient requester = new QqAccessTokenClient(options, clock);
            TokenProvider provider = new SingleFlightTokenProvider(
                    CREDENTIALS, requester, options.tokenRefreshSkew(), clock);

            AccessToken initial = provider.getAccessToken().toCompletableFuture().join();
            clock.advance(Duration.ofSeconds(59));
            AccessToken cached = provider.getAccessToken().toCompletableFuture().join();
            clock.advance(Duration.ofSeconds(1));
            AccessToken refreshed = provider.getAccessToken().toCompletableFuture().join();

            assertThat(cached).isSameAs(initial);
            assertThat(refreshed).isNotSameAs(initial);
            assertThat(refreshed.expiresAt()).isEqualTo(NOW.plusSeconds(180));
            assertThat(requests).hasValue(2);
        }
    }

    @Test
    void refreshesAfterTheCurrentTokenIsInvalidated() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AccessTokenRequester requester = credentials -> {
            int request = requests.incrementAndGet();
            return CompletableFuture.completedFuture(
                    AccessToken.of("token-" + request, NOW.plusSeconds(7200)));
        };
        QqClientOptions options = QqClientOptions.builder()
                .tokenRefreshSkew(Duration.ofSeconds(60))
                .build();
        TokenProvider provider = new SingleFlightTokenProvider(
                CREDENTIALS, requester, options.tokenRefreshSkew(), new MutableClock(NOW));

        AccessToken first = provider.getAccessToken().toCompletableFuture().join();
        provider.invalidate(first);
        AccessToken second = provider.getAccessToken().toCompletableFuture().join();
        provider.invalidate(first);
        AccessToken stillSecond = provider.getAccessToken().toCompletableFuture().join();

        assertThat(second).isNotSameAs(first);
        assertThat(stillSecond).isSameAs(second);
        assertThat(requests).hasValue(2);
    }

    @Test
    void classifiesHttp200BusinessErrorAsSanitizedAuthenticationFailure() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token", exchange -> TestHttpServer.respond(
                    exchange,
                    200,
                    "{\"code\":11241,\"message\":\"rejected client-secret-value remote-detail\"}"));
            QqAccessTokenClient client = new QqAccessTokenClient(
                    options(server, Duration.ofSeconds(60)), new MutableClock(NOW));

            QqClientException exception = failureOf(client.requestToken(CREDENTIALS));

            assertThat(exception.failure()).isEqualTo(QqClientFailure.AUTHENTICATION);
            assertThat(exception.httpStatus()).isEmpty();
            assertThat(exception.qqCode()).hasValue(11241);
            assertThat(exception.qqMessage()).isEmpty();
            assertThat(exception.toString())
                    .doesNotContain("client-secret-value")
                    .doesNotContain("remote-detail");
        }
    }

    @Test
    void treatsMissingTokenWithoutNonzeroBusinessCodeAsProtocolFailure() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.handle("/token", exchange -> TestHttpServer.respond(
                    exchange, 200, "{\"code\":0,\"message\":\"ok\"}"));
            QqAccessTokenClient client = new QqAccessTokenClient(
                    options(server, Duration.ofSeconds(60)), new MutableClock(NOW));

            QqClientException exception = failureOf(client.requestToken(CREDENTIALS));

            assertThat(exception.failure()).isEqualTo(QqClientFailure.PROTOCOL);
            assertThat(exception.qqCode()).isEmpty();
            assertThat(exception.qqMessage()).isEmpty();
        }
    }

    private static QqClientOptions options(TestHttpServer server, Duration skew) {
        return QqClientOptions.builder()
                .tokenEndpoint(server.uri("/token"))
                .openApiBaseUri(server.uri("/"))
                .requestTimeout(Duration.ofSeconds(2))
                .tokenRefreshSkew(skew)
                .build();
    }

    private static QqClientException failureOf(CompletionStage<?> stage) {
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
