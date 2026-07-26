package com.mieai.qqbot.gateway

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class GatewayCloseClassifierTest {
    @ParameterizedTest
    @CsvSource(
        "1000, true, RESUME", "1000, false, IDENTIFY", "4004, true, IDENTIFY",
        "4001, true, STOP", "4002, false, STOP", "4006, true, IDENTIFY",
        "4007, true, IDENTIFY", "4008, true, IDENTIFY", "4009, true, RESUME",
        "4009, false, IDENTIFY", "4010, true, STOP", "4014, true, STOP",
        "4900, true, IDENTIFY", "4913, true, IDENTIFY", "4914, false, STOP",
        "4915, false, STOP", "1006, true, RESUME", "1006, false, IDENTIFY",
    )
    fun classifiesOfficialAndTransientCloseCodes(
        statusCode: Int,
        resumable: Boolean,
        expected: GatewayCloseDisposition,
    ) {
        assertThat(GatewayCloseClassifier.classify(statusCode, resumable).disposition)
            .isEqualTo(expected)
    }

    @Test
    fun distinguishesRateLimitedCloseForBackoffPolicy() {
        assertThat(GatewayCloseClassifier.classify(4008, true).reconnectCause)
            .isEqualTo(GatewayReconnectCause.RATE_LIMITED)
    }

    @Test
    fun classifiesAuthenticationCloseForTokenRefresh() {
        assertThat(GatewayCloseClassifier.classify(4004, true).reconnectCause)
            .isEqualTo(GatewayReconnectCause.AUTHENTICATION_FAILURE)
    }

    @Test
    fun providesBoundedExponentialBackoff() {
        val strategy = GatewayBackoffStrategy.exponential(Duration.ofSeconds(1), Duration.ofSeconds(5))

        assertThat(strategy.delayForAttempt(1, GatewayReconnectCause.CONNECTION_CLOSED)).isEqualTo(Duration.ofSeconds(1))
        assertThat(strategy.delayForAttempt(2, GatewayReconnectCause.CONNECTION_CLOSED)).isEqualTo(Duration.ofSeconds(2))
        assertThat(strategy.delayForAttempt(3, GatewayReconnectCause.CONNECTION_CLOSED)).isEqualTo(Duration.ofSeconds(4))
        assertThat(strategy.delayForAttempt(4, GatewayReconnectCause.CONNECTION_CLOSED)).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun appliesInjectableJitterAfterBoundingExponentialDelay() {
        val calculatedDelay = AtomicReference<Duration>()
        val strategy = GatewayBackoffStrategy.exponential(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            { delay -> delay.also(calculatedDelay::set).dividedBy(2L) },
        )

        assertThat(strategy.delayForAttempt(4, GatewayReconnectCause.CONNECTION_CLOSED))
            .isEqualTo(Duration.ofMillis(2500))
        assertThat(calculatedDelay).hasValue(Duration.ofSeconds(5))
    }

    @Test
    fun rejectsJitterOutsideCalculatedDelay() {
        val strategy = GatewayBackoffStrategy.exponential(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            { delay -> delay.plusNanos(1L) },
        )

        assertThatIllegalArgumentException().isThrownBy {
            strategy.delayForAttempt(1, GatewayReconnectCause.CONNECTION_CLOSED)
        }
    }
}
