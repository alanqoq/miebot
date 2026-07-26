package com.mieai.qqbot.admin.database

import java.time.Instant

data class DatabaseTestResult(
    val success: Boolean,
    val type: DatabaseType,
    val databaseProduct: String?,
    val databaseVersion: String?,
    val latencyMs: Long,
    val readVerified: Boolean,
    val writeVerified: Boolean,
    val schemaState: DatabaseSchemaState,
    val schemaVersion: String?,
    val initialized: Boolean,
    val adminSeeded: Boolean,
    val botCount: Long,
    val testedAt: Instant,
)
