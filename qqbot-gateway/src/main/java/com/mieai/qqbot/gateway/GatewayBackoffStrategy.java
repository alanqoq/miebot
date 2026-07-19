package com.mieai.qqbot.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Injectable reconnect delay policy. Attempts are one-based. */
@FunctionalInterface
public interface GatewayBackoffStrategy {
    Duration delayForAttempt(int attempt, GatewayReconnectCause cause);

    static GatewayBackoffStrategy fixed(Duration delay) {
        Duration checked = requireNonNegative(delay, "delay");
        return (attempt, cause) -> {
            requireAttempt(attempt);
            Objects.requireNonNull(cause, "cause must not be null");
            return checked;
        };
    }

    static GatewayBackoffStrategy exponential(Duration initial, Duration maximum) {
        return exponential(initial, maximum, UnaryOperator.identity());
    }

    /**
     * Creates bounded exponential backoff and applies an injectable jitter policy to each delay.
     * The jitter result must stay between zero and the calculated exponential delay.
     */
    static GatewayBackoffStrategy exponential(
            Duration initial, Duration maximum, UnaryOperator<Duration> jitter) {
        Duration checkedInitial = requireNonNegative(initial, "initial");
        Duration checkedMaximum = requireNonNegative(maximum, "maximum");
        Objects.requireNonNull(jitter, "jitter must not be null");
        if (checkedInitial.compareTo(checkedMaximum) > 0) {
            throw new IllegalArgumentException("initial must not exceed maximum");
        }
        return (attempt, cause) -> {
            requireAttempt(attempt);
            Objects.requireNonNull(cause, "cause must not be null");
            Duration result = checkedInitial;
            for (int index = 1; index < attempt && result.compareTo(checkedMaximum) < 0; index++) {
                try {
                    result = result.multipliedBy(2L);
                } catch (ArithmeticException exception) {
                    result = checkedMaximum;
                    break;
                }
            }
            Duration bounded = result.compareTo(checkedMaximum) > 0 ? checkedMaximum : result;
            Duration jittered = Objects.requireNonNull(
                    jitter.apply(bounded), "jitter must not return null");
            if (jittered.isNegative() || jittered.compareTo(bounded) > 0) {
                throw new IllegalArgumentException(
                        "jitter must return a delay between zero and the exponential delay");
            }
            return jittered;
        };
    }

    private static void requireAttempt(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
