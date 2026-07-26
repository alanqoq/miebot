package com.mieai.qqbot.admin.database


data class DatabaseSettings(
    val type: DatabaseType,
    val sqlitePath: String?,
    val busyTimeoutMs: Long?,
    val host: String?,
    val port: Int?,
    val databaseName: String?,
    val username: String?,
    val password: DatabasePassword?,
    val sslMode: DatabaseSslMode?,
    val connectTimeoutMs: Long?,
)
