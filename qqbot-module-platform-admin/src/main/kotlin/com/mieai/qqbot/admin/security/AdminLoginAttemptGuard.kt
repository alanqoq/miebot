package com.mieai.qqbot.admin.security

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Shared JDBC login throttle; tests may use the in-memory fallback constructor. */
@Component
class AdminLoginAttemptGuard private constructor(
    private val clock: Clock,
    private val jdbc: JdbcTemplate?,
    private val transaction: TransactionTemplate?,
) {
    private val attempts = HashMap<String, Attempt>()

    @Autowired
    constructor(dataSource: DataSource) : this(dataSource, Clock.systemUTC())

    constructor(dataSource: DataSource, clock: Clock) : this(
        clock,
        JdbcTemplate(dataSource),
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
    )

    constructor(clock: Clock) : this(clock, null, null)

    @Synchronized
    fun check(remoteAddress: String?, username: String) {
        if (jdbc != null) {
            checkJdbc(remoteAddress, username)
            return
        }
        val key = key(remoteAddress, username)
        val attempt = attempts[key] ?: return
        val now = clock.instant()
        val blockedUntil = attempt.blockedUntil
        if (blockedUntil != null && blockedUntil.isAfter(now)) throw throttled(now, blockedUntil)
        if (blockedUntil != null) attempts.remove(key)
    }

    @Synchronized
    fun failed(remoteAddress: String?, username: String) {
        if (jdbc != null) {
            failedJdbc(remoteAddress, username)
            return
        }
        val key = key(remoteAddress, username)
        val now = clock.instant()
        val failures = (attempts[key]?.failures ?: 0) + 1
        val blockedUntil = if (failures >= MAX_FAILURES) now.plus(BLOCK_DURATION) else null
        attempts[key] = Attempt(failures, blockedUntil, now)
        prune(now)
        if (blockedUntil != null) throw throttled(now, blockedUntil)
    }

    @Synchronized
    fun succeeded(remoteAddress: String?, username: String) {
        val template = jdbc
        if (template != null) {
            template.update(
                "DELETE FROM admin_login_attempts WHERE attempt_key=?",
                keyHash(remoteAddress, username),
            )
            return
        }
        attempts.remove(key(remoteAddress, username))
    }

    private fun checkJdbc(remoteAddress: String?, username: String) {
        val template = requireNotNull(jdbc)
        val key = keyHash(remoteAddress, username)
        val row = template.query(
            "SELECT failures, blocked_until FROM admin_login_attempts WHERE attempt_key=?",
            ATTEMPT_ROW_MAPPER,
            key,
        ).firstOrNull()
        val blockedText = row?.blockedUntil ?: return
        val blocked = UtcTimestampCodec.parse(blockedText)
        val now = clock.instant()
        if (blocked.isAfter(now)) throw throttled(now, blocked)
        template.update("DELETE FROM admin_login_attempts WHERE attempt_key=?", key)
    }

    private fun failedJdbc(remoteAddress: String?, username: String) {
        val template = requireNotNull(jdbc)
        val transactionTemplate = requireNotNull(transaction)
        val key = keyHash(remoteAddress, username)
        val now = clock.instant()
        val blocked = now.plus(BLOCK_DURATION)
        transactionTemplate.executeWithoutResult {
            template.update(
                "DELETE FROM admin_login_attempts WHERE last_touched < ?",
                UtcTimestampCodec.format(now.minus(RETENTION)),
            )
            val updated = template.update(
                """
                UPDATE admin_login_attempts
                SET failures=failures+1,
                    blocked_until=CASE WHEN failures+1 >= ? THEN ? ELSE blocked_until END,
                    last_touched=?
                WHERE attempt_key=?
                """.trimIndent(),
                MAX_FAILURES,
                UtcTimestampCodec.format(blocked),
                UtcTimestampCodec.format(now),
                key,
            )
            if (updated == 0) {
                try {
                    template.update(
                        "INSERT INTO admin_login_attempts(attempt_key, failures, blocked_until, last_touched) " +
                            "VALUES (?, 1, NULL, ?)",
                        key,
                        UtcTimestampCodec.format(now),
                    )
                } catch (race: DataAccessException) {
                    if (!DatabaseExceptionClassifier.isDuplicateKey(race)) throw race
                    template.update(
                        """
                        UPDATE admin_login_attempts
                        SET failures=failures+1,
                            blocked_until=CASE WHEN failures+1 >= ? THEN ? ELSE blocked_until END,
                            last_touched=?
                        WHERE attempt_key=?
                        """.trimIndent(),
                        MAX_FAILURES,
                        UtcTimestampCodec.format(blocked),
                        UtcTimestampCodec.format(now),
                        key,
                    )
                }
            }
        }
        val row = template.query(
            "SELECT failures, blocked_until FROM admin_login_attempts WHERE attempt_key=?",
            ATTEMPT_ROW_MAPPER,
            key,
        ).firstOrNull()
        if (row != null && row.failures >= MAX_FAILURES) {
            val until = row.blockedUntil?.let(UtcTimestampCodec::parse) ?: blocked
            throw throttled(now, until)
        }
    }

    private fun prune(now: Instant) {
        if (attempts.size <= MAX_TRACKED_KEYS) return
        val cutoff = now.minus(RETENTION)
        attempts.entries.removeAll { it.value.lastTouched.isBefore(cutoff) }
        while (attempts.size > MAX_TRACKED_KEYS) {
            val oldest = attempts.entries.minByOrNull { it.value.lastTouched }?.key ?: return
            attempts.remove(oldest)
        }
    }

    private data class Attempt(
        val failures: Int,
        val blockedUntil: Instant?,
        val lastTouched: Instant,
    )

    private data class AttemptRow(
        val failures: Int,
        val blockedUntil: String?,
    )

    private companion object {
        const val MAX_FAILURES = 5
        const val MAX_TRACKED_KEYS = 10_000
        val BLOCK_DURATION: Duration = Duration.ofMinutes(15)
        val RETENTION: Duration = Duration.ofHours(1)
        val ATTEMPT_ROW_MAPPER: RowMapper<AttemptRow> = RowMapper { resultSet, _ ->
            AttemptRow(resultSet.getInt(1), resultSet.getString(2))
        }

        fun throttled(now: Instant, blockedUntil: Instant): LoginThrottledException =
            LoginThrottledException(now.until(blockedUntil, ChronoUnit.SECONDS))

        fun key(remoteAddress: String?, username: String): String {
            val address = remoteAddress?.takeIf { it.isNotBlank() } ?: "unknown"
            return "$address\u0000$username"
        }

        fun keyHash(remoteAddress: String?, username: String): String {
            val value = key(remoteAddress, username)
            val digest = try {
                MessageDigest.getInstance("SHA-256")
            } catch (error: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", error)
            }
            return HexFormat.of().formatHex(digest.digest(value.toByteArray(StandardCharsets.UTF_8)))
        }
    }
}
