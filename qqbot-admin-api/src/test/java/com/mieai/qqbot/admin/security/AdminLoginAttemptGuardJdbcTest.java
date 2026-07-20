package com.mieai.qqbot.admin.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminLoginAttemptGuardJdbcTest {
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void sharesLoginBlockingAcrossApplicationInstances() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + directory.resolve("login-throttle.db"));
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE admin_login_attempts (
                    attempt_key TEXT NOT NULL PRIMARY KEY,
                    failures INTEGER NOT NULL CHECK (failures >= 1),
                    blocked_until TEXT,
                    last_touched TEXT NOT NULL
                ) STRICT
                """);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminLoginAttemptGuard first = new AdminLoginAttemptGuard(dataSource, clock);
        AdminLoginAttemptGuard second = new AdminLoginAttemptGuard(dataSource, clock);

        for (int attempt = 1; attempt < 5; attempt++) {
            assertThatCode(() -> first.failed("203.0.113.7", "admin"))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> first.failed("203.0.113.7", "admin"))
                .isInstanceOf(LoginThrottledException.class);
        assertThatThrownBy(() -> second.check("203.0.113.7", "admin"))
                .isInstanceOf(LoginThrottledException.class);

        second.succeeded("203.0.113.7", "admin");
        assertThatCode(() -> first.check("203.0.113.7", "admin"))
                .doesNotThrowAnyException();
    }
}
