package com.mieai.qqbot.persistence.admin;

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC repository for the phase-one single-administrator model. */
public final class JdbcAdminUserRepository implements AdminUserRepository {
    private final JdbcTemplate jdbc;

    public JdbcAdminUserRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public boolean exists() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_users", Long.class);
        return count != null && count > 0;
    }

    @Override
    public java.util.Optional<AdminUser> findByUsername(String username) {
        Objects.requireNonNull(username, "username must not be null");
        List<AdminUser> matches = jdbc.query("""
                        SELECT id, username, password_hash, enabled, created_at, updated_at
                        FROM admin_users
                        WHERE LOWER(username) = LOWER(?)
                        """,
                (resultSet, rowNumber) -> new AdminUser(
                        java.util.UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        resultSet.getBoolean("enabled"),
                        java.time.Instant.parse(resultSet.getString("created_at")),
                        java.time.Instant.parse(resultSet.getString("updated_at"))),
                username);
        return matches.stream().findFirst();
    }

    @Override
    public boolean insertFirst(AdminUser user) {
        Objects.requireNonNull(user, "user must not be null");
        try {
            int inserted = jdbc.update("""
                            INSERT INTO admin_users (
                                id, username, password_hash, role, enabled,
                                singleton_key, created_at, updated_at
                            )
                            VALUES (?, ?, ?, 'ADMIN', ?, 1, ?, ?)
                            """,
                    user.id().toString(),
                    user.username(),
                    user.passwordHash(),
                    user.enabled() ? 1 : 0,
                    user.createdAt().toString(),
                    user.updatedAt().toString());
            return inserted == 1;
        } catch (DataAccessException exception) {
            if (!DatabaseExceptionClassifier.isDuplicateKey(exception)) {
                throw exception;
            }
            return false;
        }
    }

    @Override
    public boolean updatePassword(java.util.UUID id, String passwordHash, java.time.Instant updatedAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        int updated = jdbc.update("""
                UPDATE admin_users SET password_hash = ?, updated_at = ? WHERE id = ? AND enabled = 1
                """, passwordHash, updatedAt.toString(), id.toString());
        return updated == 1;
    }
}
