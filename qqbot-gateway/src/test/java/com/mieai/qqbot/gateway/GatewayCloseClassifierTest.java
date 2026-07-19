package com.mieai.qqbot.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GatewayCloseClassifierTest {
    @ParameterizedTest
    @CsvSource({
        "1000, true, RESUME",
        "1000, false, IDENTIFY",
        "4004, true, IDENTIFY",
        "4001, true, STOP",
        "4002, false, STOP",
        "4006, true, IDENTIFY",
        "4007, true, IDENTIFY",
        "4008, true, IDENTIFY",
        "4009, true, RESUME",
        "4009, false, IDENTIFY",
        "4010, true, STOP",
        "4014, true, STOP",
        "4900, true, IDENTIFY",
        "4913, true, IDENTIFY",
        "4914, false, STOP",
        "4915, false, STOP",
        "1006, true, RESUME",
        "1006, false, IDENTIFY"
    })
    void classifiesOfficialAndTransientCloseCodes(
            int statusCode, boolean resumable, GatewayCloseDisposition expected) {
        assertThat(GatewayCloseClassifier.classify(statusCode, resumable).disposition())
                .isEqualTo(expected);
    }

    @Test
    void distinguishesRateLimitedCloseForBackoffPolicy() {
        GatewayCloseDecision decision = GatewayCloseClassifier.classify(4008, true);

        assertThat(decision.reconnectCause()).isEqualTo(GatewayReconnectCause.RATE_LIMITED);
    }

    @Test
    void classifiesAuthenticationCloseForTokenRefresh() {
        GatewayCloseDecision decision = GatewayCloseClassifier.classify(4004, true);

        assertThat(decision.reconnectCause())
                .isEqualTo(GatewayReconnectCause.AUTHENTICATION_FAILURE);
    }

    @Test
    void providesBoundedExponentialBackoff() {
        GatewayBackoffStrategy strategy = GatewayBackoffStrategy.exponential(
                Duration.ofSeconds(1), Duration.ofSeconds(5));

        assertThat(strategy.delayForAttempt(1, GatewayReconnectCause.CONNECTION_CLOSED))
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(strategy.delayForAttempt(2, GatewayReconnectCause.CONNECTION_CLOSED))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(strategy.delayForAttempt(3, GatewayReconnectCause.CONNECTION_CLOSED))
                .isEqualTo(Duration.ofSeconds(4));
        assertThat(strategy.delayForAttempt(4, GatewayReconnectCause.CONNECTION_CLOSED))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void appliesInjectableJitterAfterBoundingExponentialDelay() {
        AtomicReference<Duration> calculatedDelay = new AtomicReference<>();
        GatewayBackoffStrategy strategy = GatewayBackoffStrategy.exponential(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                delay -> {
                    calculatedDelay.set(delay);
                    return delay.dividedBy(2L);
                });

        assertThat(strategy.delayForAttempt(4, GatewayReconnectCause.CONNECTION_CLOSED))
                .isEqualTo(Duration.ofMillis(2500));
        assertThat(calculatedDelay).hasValue(Duration.ofSeconds(5));
    }

    @Test
    void rejectsJitterOutsideCalculatedDelay() {
        GatewayBackoffStrategy strategy = GatewayBackoffStrategy.exponential(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                delay -> delay.plusNanos(1L));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> strategy.delayForAttempt(
                        1, GatewayReconnectCause.CONNECTION_CLOSED));
    }
}
