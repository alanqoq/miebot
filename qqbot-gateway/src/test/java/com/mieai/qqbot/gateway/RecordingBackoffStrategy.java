package com.mieai.qqbot.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class RecordingBackoffStrategy implements GatewayBackoffStrategy {
    private final Duration delay;
    private final List<Integer> attempts = new ArrayList<>();
    private final List<GatewayReconnectCause> causes = new ArrayList<>();

    RecordingBackoffStrategy(Duration delay) {
        this.delay = delay;
    }

    @Override
    public Duration delayForAttempt(int attempt, GatewayReconnectCause cause) {
        attempts.add(attempt);
        causes.add(cause);
        return delay;
    }

    List<Integer> attempts() {
        return List.copyOf(attempts);
    }

    List<GatewayReconnectCause> causes() {
        return List.copyOf(causes);
    }
}
