package com.mieai.qqbot.app.database

import com.mieai.qqbot.admin.database.DatabaseSettings
import com.mieai.qqbot.admin.database.DatabaseSslMode
import com.mieai.qqbot.admin.database.DatabaseType
import java.nio.file.Path
import java.time.Duration
import java.util.Arrays

class DatabaseProfile private constructor(
    val type: DatabaseType,
    val sqlitePath: Path?,
    val busyTimeout: Duration?,
    val host: String?,
    val port: Int,
    val databaseName: String?,
    val username: String?,
    password: CharArray?,
    val sslMode: DatabaseSslMode?,
    val connectTimeout: Duration?,
) : AutoCloseable {
    private var password: CharArray? = password?.clone()

    init {
        validate()
    }

    @Synchronized
    fun copyPassword(): CharArray? {
        if (type == DatabaseType.SQLITE) return null
        return password?.clone() ?: throw IllegalStateException("database password has been destroyed")
    }

    fun hasPassword(): Boolean = type != DatabaseType.SQLITE

    fun sameCredentialScope(settings: DatabaseSettings?): Boolean = settings != null &&
        type == settings.type &&
        type != DatabaseType.SQLITE &&
        host == settings.host &&
        port == settings.port &&
        databaseName == settings.databaseName &&
        username == settings.username

    fun copy(): DatabaseProfile {
        val copiedPassword = copyPassword()
        try {
            return if (type == DatabaseType.SQLITE) {
                sqlite(requireNotNull(sqlitePath), requireNotNull(busyTimeout))
            } else {
                server(
                    type,
                    requireNotNull(host),
                    port,
                    requireNotNull(databaseName),
                    requireNotNull(username),
                    requireNotNull(copiedPassword),
                    requireNotNull(sslMode),
                    requireNotNull(connectTimeout),
                )
            }
        } finally {
            copiedPassword?.let { Arrays.fill(it, '\u0000') }
        }
    }

    @Synchronized
    override fun close() {
        password?.let { Arrays.fill(it, '\u0000') }
        password = null
    }

    override fun toString(): String = "DatabaseProfile[type=$type, password=<redacted>]"

    private fun validate() {
        if (type == DatabaseType.SQLITE) {
            require(sqlitePath != null && busyTimeout != null && !busyTimeout.isNegative && !busyTimeout.isZero) {
                "SQLite path and positive busy timeout are required"
            }
            return
        }
        require(port in 1..65535) { "database port must be between 1 and 65535" }
        val timeout = requireNotNull(connectTimeout)
        require(!timeout.isNegative && !timeout.isZero) { "connect timeout must be positive" }
        val serverHost = requireNotNull(host)
        require(
            serverHost.codePoints().noneMatch(Character::isWhitespace) &&
                '/' !in serverHost && '?' !in serverHost && '#' !in serverHost,
        ) { "database host contains unsupported characters" }
    }

    companion object {
        fun sqlite(path: Path, busyTimeout: Duration): DatabaseProfile = DatabaseProfile(
            DatabaseType.SQLITE,
            path.toAbsolutePath().normalize(),
            busyTimeout,
            null,
            0,
            null,
            null,
            null,
            null,
            null,
        )

        fun server(
            type: DatabaseType,
            host: String,
            port: Int,
            databaseName: String,
            username: String,
            password: CharArray,
            sslMode: DatabaseSslMode,
            connectTimeout: Duration,
        ): DatabaseProfile {
            require(type != DatabaseType.SQLITE) { "server profile type must not be SQLITE" }
            return DatabaseProfile(
                type,
                null,
                null,
                requireText(host, "host"),
                port,
                requireText(databaseName, "databaseName"),
                requireText(username, "username"),
                password,
                sslMode,
                connectTimeout,
            )
        }

        fun fromSettings(settings: DatabaseSettings, resolvedPassword: CharArray?): DatabaseProfile =
            if (settings.type == DatabaseType.SQLITE) {
                sqlite(
                    Path.of(requireNotNull(settings.sqlitePath)),
                    Duration.ofMillis(requireNotNull(settings.busyTimeoutMs)),
                )
            } else {
                server(
                    settings.type,
                    requireNotNull(settings.host),
                    requireNotNull(settings.port),
                    requireNotNull(settings.databaseName),
                    requireNotNull(settings.username),
                    requireNotNull(resolvedPassword),
                    requireNotNull(settings.sslMode),
                    Duration.ofMillis(requireNotNull(settings.connectTimeoutMs)),
                )
            }

        private fun requireText(value: String, name: String): String {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "$name must not be blank" }
            return normalized
        }
    }
}
