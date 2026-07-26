package com.mieai.qqbot.admin.security

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminLoginAttemptGuardJdbcTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `shares login blocking across application instances`() {
        val dataSource = DriverManagerDataSource("jdbc:sqlite:${directory.resolve("login-throttle.db")}")
        JdbcTemplate(dataSource).execute(
            """
            CREATE TABLE admin_login_attempts (
                attempt_key TEXT NOT NULL PRIMARY KEY,
                failures INTEGER NOT NULL CHECK (failures >= 1),
                blocked_until TEXT,
                last_touched TEXT NOT NULL
            ) STRICT
            """.trimIndent(),
        )
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val first = AdminLoginAttemptGuard(dataSource, clock)
        val second = AdminLoginAttemptGuard(dataSource, clock)

        for (attempt in 1 until 5) {
            assertThatCode { first.failed("203.0.113.7", "admin") }.doesNotThrowAnyException()
        }
        assertThatThrownBy { first.failed("203.0.113.7", "admin") }
            .isInstanceOf(LoginThrottledException::class.java)
        assertThatThrownBy { second.check("203.0.113.7", "admin") }
            .isInstanceOf(LoginThrottledException::class.java)

        second.succeeded("203.0.113.7", "admin")
        assertThatCode { first.check("203.0.113.7", "admin") }.doesNotThrowAnyException()
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-20T12:00:00Z")
    }
}
