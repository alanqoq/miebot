package com.mieai.qqbot.admin.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Small in-memory guard for the single-node SQLite deployment profile. */
@Component
final class AdminLoginAttemptGuard {
    private static final int MAX_FAILURES = 5;
    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration RETENTION = Duration.ofHours(1);

    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Clock clock;

    AdminLoginAttemptGuard() {
        this(Clock.systemUTC());
    }

    AdminLoginAttemptGuard(Clock clock) {
        this.clock = clock;
    }

    synchronized void check(String remoteAddress, String username) {
        String key = key(remoteAddress, username);
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        Instant now = clock.instant();
        if (attempt.blockedUntil() != null && attempt.blockedUntil().isAfter(now)) {
            throw throttled(now, attempt.blockedUntil());
        }
        if (attempt.blockedUntil() != null) {
            attempts.remove(key);
        }
    }

    synchronized void failed(String remoteAddress, String username) {
        String key = key(remoteAddress, username);
        Instant now = clock.instant();
        Attempt previous = attempts.get(key);
        int failures = previous == null ? 1 : previous.failures() + 1;
        Instant blockedUntil = failures >= MAX_FAILURES ? now.plus(BLOCK_DURATION) : null;
        attempts.put(key, new Attempt(failures, blockedUntil, now));
        prune(now);
        if (blockedUntil != null) {
            throw throttled(now, blockedUntil);
        }
    }

    synchronized void succeeded(String remoteAddress, String username) {
        attempts.remove(key(remoteAddress, username));
    }

    private void prune(Instant now) {
        if (attempts.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        Instant cutoff = now.minus(RETENTION);
        attempts.entrySet().removeIf(entry -> entry.getValue().lastTouched().isBefore(cutoff));
        while (attempts.size() > MAX_TRACKED_KEYS) {
            String oldest = attempts.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparing(Attempt::lastTouched)))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            attempts.remove(oldest);
        }
    }

    private static LoginThrottledException throttled(Instant now, Instant blockedUntil) {
        long seconds = now.until(blockedUntil, ChronoUnit.SECONDS);
        return new LoginThrottledException(seconds);
    }

    private static String key(String remoteAddress, String username) {
        String address = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        return address + '\0' + username;
    }

    private record Attempt(int failures, Instant blockedUntil, Instant lastTouched) {}
}
