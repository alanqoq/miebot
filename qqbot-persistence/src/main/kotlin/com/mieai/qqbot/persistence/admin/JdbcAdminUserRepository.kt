package com.mieai.qqbot.persistence.admin

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/** JDBC repository for the phase-one single-administrator model. */
class JdbcAdminUserRepository(dataSource: DataSource) : AdminUserRepository {
    private val jdbc = JdbcTemplate(dataSource)

    override fun exists(): Boolean {
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", java.lang.Long::class.java)
        return count != null && count > 0
    }

    override fun findByUsername(username: String): AdminUser? {
        val matches: List<AdminUser> = jdbc.query(
            """
            SELECT id, username, password_hash, enabled, created_at, updated_at
            FROM admin_users
            WHERE LOWER(username) = LOWER(?)
            """.trimIndent(),
            RowMapper { resultSet, _ ->
                AdminUser(
                    UUID.fromString(resultSet.getString("id")),
                    resultSet.getString("username"),
                    resultSet.getString("password_hash"),
                    resultSet.getBoolean("enabled"),
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at")),
                )
            },
            username,
        )
        return matches.firstOrNull()
    }

    override fun insertFirst(user: AdminUser): Boolean {
        return try {
            val inserted = jdbc.update(
                """
                INSERT INTO admin_users (
                    id, username, password_hash, role, enabled,
                    singleton_key, created_at, updated_at
                )
                VALUES (?, ?, ?, 'ADMIN', ?, 1, ?, ?)
                """.trimIndent(),
                user.id.toString(),
                user.username,
                user.passwordHash,
                if (user.enabled) 1 else 0,
                user.createdAt.toString(),
                user.updatedAt.toString(),
            )
            inserted == 1
        } catch (exception: DataAccessException) {
            if (!DatabaseExceptionClassifier.isDuplicateKey(exception)) {
                throw exception
            }
            false
        }
    }

    override fun updatePassword(id: UUID, passwordHash: String, updatedAt: Instant): Boolean {
        return jdbc.update(
            "UPDATE admin_users SET password_hash = ?, updated_at = ? WHERE id = ? AND enabled = 1",
            passwordHash,
            updatedAt.toString(),
            id.toString(),
        ) == 1
    }
}
