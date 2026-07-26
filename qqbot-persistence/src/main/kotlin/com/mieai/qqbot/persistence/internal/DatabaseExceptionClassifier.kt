package com.mieai.qqbot.persistence.internal

import java.sql.SQLException
import java.util.Locale
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException

/** Normalizes unique-key violations that are not consistently translated by JDBC drivers. */
object DatabaseExceptionClassifier {
    fun isDuplicateKey(exception: DataAccessException): Boolean {
        if (exception is DuplicateKeyException) {
            return true
        }
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is SQLException && isDuplicateKey(cause)) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun isDuplicateKey(exception: SQLException): Boolean {
        val state = exception.sqlState
        val vendorCode = exception.errorCode
        if (state == "23505") {
            return true
        }
        if (vendorCode == 1062 && state != null && state.startsWith("23")) {
            return true
        }
        if (vendorCode != 19) {
            return false
        }
        val normalized = exception.message?.uppercase(Locale.ROOT) ?: return false
        return normalized.contains("SQLITE_CONSTRAINT_UNIQUE") ||
            normalized.contains("UNIQUE CONSTRAINT FAILED")
    }
}
