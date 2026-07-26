package com.mieai.qqbot.admin.database

import java.time.Instant

data class DatabaseConfigurationView(
    val revision: Long,
    val type: DatabaseType,
    val sqlitePath: String?,
    val busyTimeoutMs: Long?,
    val host: String?,
    val port: Int?,
    val databaseName: String?,
    val username: String?,
    val sslMode: DatabaseSslMode?,
    val connectTimeoutMs: Long?,
    val passwordConfigured: Boolean,
    val databaseProduct: String?,
    val databaseVersion: String?,
    val switchInProgress: Boolean,
    val lastSwitchedAt: Instant?,
)
