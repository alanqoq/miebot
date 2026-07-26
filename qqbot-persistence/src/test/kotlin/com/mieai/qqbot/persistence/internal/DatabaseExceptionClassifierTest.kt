package com.mieai.qqbot.persistence.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.UncategorizedSQLException
import java.sql.SQLException

class DatabaseExceptionClassifierTest {
    @Test
    fun recognizesSupportedDatabaseUniqueViolations() {
        assertThat(classify(SQLException("duplicate key", "23505", 0))).isTrue()
        assertThat(classify(SQLException("Duplicate entry", "23000", 1062))).isTrue()
        assertThat(
            classify(
                SQLException(
                    "[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: bots.app_id",
                    null,
                    19,
                ),
            ),
        ).isTrue()
    }

    @Test
    fun rejectsOtherIntegrityAndDatabaseFailures() {
        assertThat(
            classify(SQLException("[SQLITE_CONSTRAINT_CHECK] CHECK constraint failed", null, 19)),
        ).isFalse()
        assertThat(classify(SQLException("connection failed", "08001", 0))).isFalse()
    }

    private fun classify(cause: SQLException): Boolean = DatabaseExceptionClassifier.isDuplicateKey(
        UncategorizedSQLException("write", "INSERT", cause),
    )
}
