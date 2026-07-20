package com.mieai.qqbot.admin.security;

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared JDBC login throttle; tests may use the in-memory fallback constructor. */
@Component
final class AdminLoginAttemptGuard {
    private static final int MAX_FAILURES = 5;
    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration RETENTION = Duration.ofHours(1);

    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    @Autowired
    AdminLoginAttemptGuard(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    AdminLoginAttemptGuard(DataSource dataSource, Clock clock) {
        this.clock = clock;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    AdminLoginAttemptGuard() {
        this(Clock.systemUTC());
    }

    AdminLoginAttemptGuard(Clock clock) {
        this.clock = clock;
        this.jdbc = null;
        this.transaction = null;
    }

    synchronized void check(String remoteAddress, String username) {
        if (jdbc != null) { checkJdbc(remoteAddress, username); return; }
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
        if (jdbc != null) { failedJdbc(remoteAddress, username); return; }
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
        if (jdbc != null) {
            jdbc.update("DELETE FROM admin_login_attempts WHERE attempt_key=?", keyHash(remoteAddress, username));
            return;
        }
        attempts.remove(key(remoteAddress, username));
    }

    private void checkJdbc(String remoteAddress, String username) {
        String key = keyHash(remoteAddress, username);
        AttemptRow row = jdbc.query("SELECT failures, blocked_until FROM admin_login_attempts WHERE attempt_key=?",
                (rs, n) -> new AttemptRow(rs.getInt(1), rs.getString(2)), key)
                .stream().findFirst().orElse(null);
        if (row == null || row.blockedUntil() == null) return;
        Instant blocked = UtcTimestampCodec.parse(row.blockedUntil());
        Instant now = clock.instant();
        if (blocked.isAfter(now)) throw throttled(now, blocked);
        jdbc.update("DELETE FROM admin_login_attempts WHERE attempt_key=?", key);
    }

    private void failedJdbc(String remoteAddress, String username) {
        String key = keyHash(remoteAddress, username);
        Instant now = clock.instant();
        Instant blocked = now.plus(BLOCK_DURATION);
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM admin_login_attempts WHERE last_touched < ?",
                    UtcTimestampCodec.format(now.minus(RETENTION)));
            int updated = jdbc.update("""
                    UPDATE admin_login_attempts
                    SET failures=failures+1,
                        blocked_until=CASE WHEN failures+1 >= ? THEN ? ELSE blocked_until END,
                        last_touched=?
                    WHERE attempt_key=?
                    """, MAX_FAILURES, UtcTimestampCodec.format(blocked), UtcTimestampCodec.format(now), key);
            if (updated == 0) {
                try {
                    jdbc.update("INSERT INTO admin_login_attempts(attempt_key, failures, blocked_until, last_touched) VALUES (?, 1, NULL, ?)",
                            key, UtcTimestampCodec.format(now));
                } catch (DataAccessException race) {
                    if (!DatabaseExceptionClassifier.isDuplicateKey(race)) throw race;
                    jdbc.update("""
                            UPDATE admin_login_attempts
                            SET failures=failures+1,
                                blocked_until=CASE WHEN failures+1 >= ? THEN ? ELSE blocked_until END,
                                last_touched=?
                            WHERE attempt_key=?
                            """, MAX_FAILURES, UtcTimestampCodec.format(blocked),
                            UtcTimestampCodec.format(now), key);
                }
            }
        });
        AttemptRow row = jdbc.query("SELECT failures, blocked_until FROM admin_login_attempts WHERE attempt_key=?",
                (rs, n) -> new AttemptRow(rs.getInt(1), rs.getString(2)), key)
                .stream().findFirst().orElse(null);
        if (row != null && row.failures() >= MAX_FAILURES) {
            Instant until = row.blockedUntil() == null ? blocked : UtcTimestampCodec.parse(row.blockedUntil());
            throw throttled(now, until);
        }
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

    private static String keyHash(String remoteAddress, String username) {
        String value = key(remoteAddress, username);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Attempt(int failures, Instant blockedUntil, Instant lastTouched) {}
    private record AttemptRow(int failures, String blockedUntil) {}
}
