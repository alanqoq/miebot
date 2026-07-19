package com.mieai.qqbot.persistence.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

class DatabaseExceptionClassifierTest {
    @Test
    void recognizesSupportedDatabaseUniqueViolations() {
        assertThat(classify(new SQLException("duplicate key", "23505", 0))).isTrue();
        assertThat(classify(new SQLException("Duplicate entry", "23000", 1062))).isTrue();
        assertThat(classify(new SQLException(
                "[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: bots.app_id", null, 19)))
                .isTrue();
    }

    @Test
    void rejectsOtherIntegrityAndDatabaseFailures() {
        assertThat(classify(new SQLException(
                "[SQLITE_CONSTRAINT_CHECK] CHECK constraint failed", null, 19)))
                .isFalse();
        assertThat(classify(new SQLException("connection failed", "08001", 0))).isFalse();
    }

    private static boolean classify(SQLException cause) {
        return DatabaseExceptionClassifier.isDuplicateKey(
                new UncategorizedSQLException("write", "INSERT", cause));
    }
}
