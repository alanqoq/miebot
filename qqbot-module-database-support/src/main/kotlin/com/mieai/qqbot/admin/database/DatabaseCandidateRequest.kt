package com.mieai.qqbot.admin.database

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus

data class DatabaseCandidateRequest(
    @field:NotNull val type: DatabaseType?,
    @field:Size(max = 4096) val sqlitePath: String?,
    @field:Min(100) @field:Max(60000) val busyTimeoutMs: Long?,
    @field:Size(max = 253) val host: String?,
    @field:Min(1) @field:Max(65535) val port: Int?,
    @field:Size(max = 128) val databaseName: String?,
    @field:Size(max = 128) val username: String?,
    @field:Size(max = 4096) val password: String?,
    val sslMode: DatabaseSslMode?,
    @field:Min(500) @field:Max(60000) val connectTimeoutMs: Long?,
) {
    fun toSettings(protectedPassword: DatabasePassword?): DatabaseSettings {
        val fields = validateFields()
        if (fields.isNotEmpty()) {
            throw DatabaseAdministrationException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                fields,
            )
        }
        return DatabaseSettings(
            requireNotNull(type),
            normalize(sqlitePath),
            busyTimeoutMs,
            normalize(host),
            port,
            normalize(databaseName),
            normalize(username),
            protectedPassword,
            sslMode,
            connectTimeoutMs,
        )
    }

    private fun validateFields(): Map<String, String> = linkedMapOf<String, String>().also { fields ->
        if (type == DatabaseType.SQLITE) {
            requireText(fields, "sqlitePath", sqlitePath, "SQLite path is required")
            requireValue(fields, "busyTimeoutMs", busyTimeoutMs, "SQLite busy timeout is required")
        } else if (type != null) {
            requireText(fields, "host", host, "Database host is required")
            requireValue(fields, "port", port, "Database port is required")
            requireText(fields, "databaseName", databaseName, "Database name is required")
            requireText(fields, "username", username, "Database username is required")
            requireValue(fields, "sslMode", sslMode, "SSL mode is required")
            requireValue(fields, "connectTimeoutMs", connectTimeoutMs, "Connection timeout is required")
        }
    }

    override fun toString(): String = "DatabaseCandidateRequest[type=$type, password=<redacted>]"

    private companion object {
        fun requireText(
            fields: MutableMap<String, String>,
            name: String,
            value: String?,
            message: String,
        ) {
            when {
                value.isNullOrBlank() -> fields[name] = message
                value != value.trim() -> fields[name] = "Value must not have surrounding whitespace"
            }
        }

        fun requireValue(
            fields: MutableMap<String, String>,
            name: String,
            value: Any?,
            message: String,
        ) {
            if (value == null) fields[name] = message
        }

        fun normalize(value: String?): String? = value?.trim()
    }
}
