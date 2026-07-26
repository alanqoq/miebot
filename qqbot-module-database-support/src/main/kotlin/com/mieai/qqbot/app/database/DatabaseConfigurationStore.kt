package com.mieai.qqbot.app.database

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.admin.database.DatabaseSslMode
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.Arrays

class DatabaseConfigurationStore(
    activeFile: Path,
    candidateFile: Path,
    private val objectMapper: ObjectMapper,
    private val secretCipher: AesGcmConfigurationSecretCipher,
) {
    private val activeFile = normalize(activeFile)
    private val candidateFileValue = normalize(candidateFile)
    private val writer = objectMapper.writerWithDefaultPrettyPrinter()

    init {
        require(activeFile != candidateFileValue) {
            "active and candidate database configuration files must differ"
        }
    }

    val file: Path
        get() = activeFile

    val candidateFile: Path
        get() = candidateFileValue

    fun loadPersisted(): LoadedConfiguration? = load(activeFile, "active")

    fun loadCandidateRequired(): LoadedConfiguration = load(candidateFileValue, "candidate")
        ?: throw IllegalStateException(
            "Database candidate configuration file does not exist: $candidateFileValue",
        )

    fun loadRequired(): LoadedConfiguration = loadPersisted()
        ?: throw IllegalStateException("Database configuration file does not exist: $activeFile")

    fun prepare(profile: DatabaseProfile, revision: Long, lastSwitchedAt: Instant): PreparedConfiguration {
        require(revision >= 1L) { "revision must be positive" }
        var encrypted: EncryptedConfigurationValue? = null
        val password = profile.copyPassword()
        try {
            if (password != null) encrypted = secretCipher.encrypt(password, PASSWORD_PURPOSE)
        } finally {
            password?.let { Arrays.fill(it, '\u0000') }
        }

        val stored = StoredConfiguration.from(profile, revision, lastSwitchedAt, encrypted)
        val parent = activeFile.parent
        val created = try {
            if (parent != null) Files.createDirectories(parent)
            Files.createTempFile(
                requireNotNull(parent),
                activeFile.fileName.toString(),
                ".pending",
            )
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to prepare database configuration file", exception)
        }

        var ownershipTransferred = false
        try {
            writer.writeValue(created.toFile(), stored)
            return PreparedConfiguration(created).also { ownershipTransferred = true }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to prepare database configuration file", exception)
        } finally {
            if (!ownershipTransferred) {
                try {
                    Files.deleteIfExists(created)
                } catch (_: IOException) {
                    // Preserve the preparation failure; this file is no longer usable.
                }
            }
        }
    }

    fun commit(prepared: PreparedConfiguration) = prepared.commitTo(activeFile)

    fun fromBootstrap(properties: DatabaseBootstrapProperties): DatabaseProfile {
        val type = properties.type
        if (type == DatabaseType.SQLITE) {
            return DatabaseProfile.sqlite(properties.sqlite.path, properties.sqlite.busyTimeout)
        }
        val server = if (type == DatabaseType.MYSQL) properties.mysql else properties.postgresql
        val password = readBootstrapPassword(server)
        try {
            return DatabaseProfile.server(
                type,
                server.host,
                server.port,
                server.databaseName,
                server.username,
                password,
                server.sslMode,
                server.connectTimeout,
            )
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private fun load(source: Path, description: String): LoadedConfiguration? {
        if (!Files.exists(source)) return null
        try {
            val stored = objectMapper.readValue(source.toFile(), StoredConfiguration::class.java)
            return toLoaded(stored, source)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to read $description database configuration file", exception)
        } catch (exception: RuntimeException) {
            throw IllegalStateException("Unable to read $description database configuration file", exception)
        }
    }

    private fun toLoaded(stored: StoredConfiguration, source: Path): LoadedConfiguration {
        val revision = stored.revision ?: 1L
        require(revision >= 1L) { "database configuration revision must be positive" }
        val type = requireNotNull(stored.type) { "database configuration type is required" }
        if (type == DatabaseType.SQLITE) {
            require(stored.sqlitePath != null && stored.busyTimeoutMs != null) {
                "SQLite configuration is incomplete"
            }
            return LoadedConfiguration(
                DatabaseProfile.sqlite(
                    resolveRelativePath(stored.sqlitePath, source),
                    Duration.ofMillis(stored.busyTimeoutMs),
                ),
                revision,
                stored.lastSwitchedAt,
            )
        }

        val password = readStoredPassword(stored, source)
        try {
            val profile = DatabaseProfile.server(
                type,
                requireNotNull(stored.host),
                requireValue(stored.port, "port"),
                requireNotNull(stored.databaseName),
                requireNotNull(stored.username),
                password,
                requireNotNull(stored.sslMode) { "sslMode is required" },
                Duration.ofMillis(requireValue(stored.connectTimeoutMs, "connectTimeoutMs")),
            )
            return LoadedConfiguration(profile, revision, stored.lastSwitchedAt)
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private fun readStoredPassword(stored: StoredConfiguration, source: Path): CharArray {
        val encrypted = stored.passwordCiphertext != null || stored.passwordKeyId != null
        val sources = (if (encrypted) 1 else 0) +
            (if (stored.password == null) 0 else 1) +
            (if (stored.passwordFile == null) 0 else 1)
        require(sources == 1) { "Configure exactly one database password source" }
        if (encrypted) {
            require(stored.passwordCiphertext != null && stored.passwordKeyId != null) {
                "Encrypted database password is incomplete"
            }
            return secretCipher.decrypt(
                EncryptedConfigurationValue(stored.passwordCiphertext, stored.passwordKeyId),
                PASSWORD_PURPOSE,
            )
        }
        stored.passwordFile?.let { return readPasswordFile(resolveRelativePath(it, source)) }
        return requireNotNull(stored.password).toCharArray()
    }

    private fun readBootstrapPassword(server: DatabaseBootstrapProperties.Server): CharArray {
        val configured = server.password
        var configuredFile = server.passwordFile
        if (configuredFile?.toString()?.isBlank() == true) configuredFile = null
        if (configured.isNotEmpty() && configuredFile != null) {
            throw IllegalStateException("Configure only one database password or password file")
        }
        return if (configuredFile != null) readPasswordFile(configuredFile) else configured.toCharArray()
    }

    data class LoadedConfiguration(
        val profile: DatabaseProfile,
        val revision: Long,
        val lastSwitchedAt: Instant?,
    )

    class PreparedConfiguration internal constructor(private var temporary: Path?) : AutoCloseable {
        @Synchronized
        internal fun commitTo(target: Path) {
            val source = temporary
                ?: throw IllegalStateException("prepared configuration has already been consumed")
            try {
                try {
                    Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
                temporary = null
            } catch (exception: IOException) {
                throw IllegalStateException("Unable to commit database configuration file", exception)
            }
        }

        @Synchronized
        override fun close() {
            val source = temporary ?: return
            try {
                Files.deleteIfExists(source)
            } catch (_: IOException) {
                // Best-effort cleanup of an uncommitted candidate file.
            }
            temporary = null
        }
    }

    private data class StoredConfiguration(
        val revision: Long?,
        val type: DatabaseType?,
        val sqlitePath: String?,
        val busyTimeoutMs: Long?,
        val host: String?,
        val port: Int?,
        val databaseName: String?,
        val username: String?,
        val sslMode: DatabaseSslMode?,
        val connectTimeoutMs: Long?,
        val passwordCiphertext: String?,
        val passwordKeyId: String?,
        val password: String?,
        val passwordFile: String?,
        val lastSwitchedAt: Instant?,
    ) {
        companion object {
            fun from(
                profile: DatabaseProfile,
                revision: Long,
                lastSwitchedAt: Instant,
                encrypted: EncryptedConfigurationValue?,
            ): StoredConfiguration = StoredConfiguration(
                revision,
                profile.type,
                profile.sqlitePath?.toString(),
                profile.busyTimeout?.toMillis(),
                profile.host,
                if (profile.type == DatabaseType.SQLITE) null else profile.port,
                profile.databaseName,
                profile.username,
                profile.sslMode,
                profile.connectTimeout?.toMillis(),
                encrypted?.ciphertext,
                encrypted?.keyId,
                null,
                null,
                lastSwitchedAt,
            )
        }
    }

    companion object {
        private const val PASSWORD_PURPOSE = "database-connection-password"

        private fun resolveRelativePath(configured: String, configurationFile: Path): Path {
            val path = Path.of(configured)
            if (path.isAbsolute) return path
            return (configurationFile.parent?.resolve(path) ?: path).normalize()
        }

        private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

        private fun readPasswordFile(path: Path): CharArray {
            try {
                var value = Files.readString(path, StandardCharsets.UTF_8)
                value = when {
                    value.endsWith("\r\n") -> value.dropLast(2)
                    value.endsWith("\n") -> value.dropLast(1)
                    else -> value
                }
                return value.toCharArray()
            } catch (exception: IOException) {
                throw IllegalStateException("Unable to read database password file", exception)
            }
        }

        private fun requireValue(value: Long?, name: String): Long =
            value ?: throw IllegalArgumentException("$name is required")

        private fun requireValue(value: Int?, name: String): Int =
            value ?: throw IllegalArgumentException("$name is required")
    }
}
