package com.mieai.qqbot.persistence.internal;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/** Normalizes unique-key violations that are not consistently translated by JDBC drivers. */
public final class DatabaseExceptionClassifier {
    private DatabaseExceptionClassifier() {
    }

    public static boolean isDuplicateKey(DataAccessException exception) {
        Objects.requireNonNull(exception, "exception must not be null");
        if (exception instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && isDuplicateKey(sqlException)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDuplicateKey(SQLException exception) {
        String state = exception.getSQLState();
        int vendorCode = exception.getErrorCode();
        if ("23505".equals(state)) {
            return true;
        }
        if (vendorCode == 1062 && state != null && state.startsWith("23")) {
            return true;
        }
        if (vendorCode != 19) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toUpperCase(Locale.ROOT);
        return normalized.contains("SQLITE_CONSTRAINT_UNIQUE")
                || normalized.contains("UNIQUE CONSTRAINT FAILED");
    }
}
