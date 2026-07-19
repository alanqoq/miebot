package com.mieai.qqbot.admin.database;

import java.time.Instant;

public record DatabaseTestResult(
        boolean success,
        DatabaseType type,
        String databaseProduct,
        String databaseVersion,
        long latencyMs,
        boolean readVerified,
        boolean writeVerified,
        DatabaseSchemaState schemaState,
        String schemaVersion,
        boolean initialized,
        boolean adminSeeded,
        long botCount,
        Instant testedAt) {
}
