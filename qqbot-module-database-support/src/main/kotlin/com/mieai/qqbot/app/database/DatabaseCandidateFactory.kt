package com.mieai.qqbot.app.database

import com.mieai.qqbot.admin.database.DatabaseSchemaState
import com.mieai.qqbot.admin.database.DatabaseSslMode
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Arrays
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway

class DatabaseCandidateFactory(
    private val moduleMigrator: ModuleDatabaseMigrator? = null,
) {

    fun prepare(profile: DatabaseProfile): DatabaseCandidate {
        val started = System.nanoTime()
        val dataSource = createDataSource(profile)
        try {
            val metadata = metadata(dataSource)
            val initialState = inspectSchema(dataSource)
            val dialect = migrationDirectory(profile.type)
            val flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/$dialect")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
            val migration = flyway.migrate()
            val moduleMigrations = moduleMigrator?.migrate(dataSource, dialect) ?: 0
            verifyRequiredTables(dataSource)
            verifyWriteAndRead(dataSource)
            val botCount = count(dataSource, "bots")
            val current = flyway.info().current()
            return DatabaseCandidate(
                dataSource,
                metadata.product,
                metadata.version,
                elapsedMillis(started),
                initialState,
                current?.version?.toString(),
                migration.migrationsExecuted > 0 || moduleMigrations > 0,
                botCount,
            )
        } catch (exception: RuntimeException) {
            SwitchableDataSource.closeDataSource(dataSource)
            throw exception
        }
    }

    fun isUnwritten(dataSource: DataSource): Boolean = count(dataSource, "bots") == 0L &&
        count(dataSource, "event_inbox") == 0L &&
        count(dataSource, "outbox_jobs") == 0L &&
        count(dataSource, "plugin_artifacts") == 0L &&
        count(dataSource, "bot_plugins") == 0L &&
        count(dataSource, "plugin_deliveries") == 0L &&
        count(dataSource, "admin_users") == 0L

    private fun createDataSource(profile: DatabaseProfile): DataSource {
        if (profile.type == DatabaseType.SQLITE) {
            return SQLiteDataSourceFactory.create(
                requireNotNull(profile.sqlitePath),
                requireNotNull(profile.busyTimeout),
            )
        }

        val password = requireNotNull(profile.copyPassword())
        try {
            val timeout = requireNotNull(profile.connectTimeout).toMillis()
            val configuration = HikariConfig()
            configuration.jdbcUrl = jdbcUrl(profile)
            configuration.username = profile.username
            configuration.password = String(password)
            configuration.connectionTimeout = timeout
            configuration.validationTimeout = minOf(timeout, 5_000L)
            configuration.initializationFailTimeout = timeout
            configuration.maximumPoolSize = 8
            configuration.minimumIdle = 0
            configuration.isAutoCommit = true
            configuration.poolName = "qqbot-${profile.type.name.lowercase(Locale.ROOT)}-" +
                Integer.toUnsignedString(System.identityHashCode(profile))
            return HikariDataSource(configuration)
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private data class Metadata(val product: String, val version: String)

    companion object {
        private val REQUIRED_TABLES = setOf(
            "bots",
            "event_inbox",
            "outbox_jobs",
            "admin_users",
            "plugin_artifacts",
            "bot_plugins",
            "plugin_deliveries",
        )

        private fun jdbcUrl(profile: DatabaseProfile): String {
            val rawHost = requireNotNull(profile.host)
            val host = if (':' in rawHost && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
            val database = encodePathSegment(requireNotNull(profile.databaseName))
            val timeout = requireNotNull(profile.connectTimeout)
            return if (profile.type == DatabaseType.MYSQL) {
                "jdbc:mysql://$host:${profile.port}/$database" +
                    "?connectTimeout=${timeout.toMillis()}" +
                    "&socketTimeout=${maxOf(timeout.toMillis(), 10_000L)}" +
                    "&sslMode=${requireNotNull(profile.sslMode).name}" +
                    "&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
            } else {
                val timeoutSeconds = maxOf(1L, timeout.seconds)
                "jdbc:postgresql://$host:${profile.port}/$database" +
                    "?connectTimeout=$timeoutSeconds" +
                    "&socketTimeout=${maxOf(timeoutSeconds, 10L)}" +
                    "&sslmode=${postgresSslMode(requireNotNull(profile.sslMode))}" +
                    "&ApplicationName=qqbot-platform"
            }
        }

        private fun postgresSslMode(mode: DatabaseSslMode): String = when (mode) {
            DatabaseSslMode.DISABLED -> "disable"
            DatabaseSslMode.PREFERRED -> "prefer"
            DatabaseSslMode.REQUIRED -> "require"
            DatabaseSslMode.VERIFY_CA -> "verify-ca"
            DatabaseSslMode.VERIFY_IDENTITY -> "verify-full"
        }

        private fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun metadata(dataSource: DataSource): Metadata = try {
            dataSource.connection.use { connection ->
                val metadata = connection.metaData
                Metadata(metadata.databaseProductName, metadata.databaseProductVersion)
            }
        } catch (exception: SQLException) {
            throw DatabaseConnectionException(exception)
        }

        private fun inspectSchema(dataSource: DataSource): DatabaseSchemaState = try {
            dataSource.connection.use { connection ->
                val tables = userTables(connection)
                when {
                    tables.isEmpty() -> DatabaseSchemaState.EMPTY
                    tables.containsAll(REQUIRED_TABLES) -> DatabaseSchemaState.READY
                    "flyway_schema_history" in tables || "bots" in tables -> DatabaseSchemaState.INITIALIZED
                    else -> throw DatabaseSchemaException("Target database contains an unmanaged schema")
                }
            }
        } catch (exception: SQLException) {
            throw DatabaseReadException(exception)
        }

        @Throws(SQLException::class)
        private fun userTables(connection: Connection): Set<String> {
            val tables = mutableSetOf<String>()
            val metadata = connection.metaData
            val catalog = connection.catalog
            val schema = try {
                connection.schema
            } catch (_: SQLException) {
                null
            } catch (_: AbstractMethodError) {
                null
            }
            metadata.getTables(catalog, schema, "%", arrayOf("TABLE")).use { result ->
                while (result.next()) {
                    val tableSchema = result.getString("TABLE_SCHEM")
                    val name = result.getString("TABLE_NAME")
                    if (name != null && !isSystemTable(tableSchema, name)) {
                        tables += name.lowercase(Locale.ROOT)
                    }
                }
            }
            return tables
        }

        private fun isSystemTable(schema: String?, table: String): Boolean {
            val normalizedSchema = schema?.lowercase(Locale.ROOT).orEmpty()
            val normalizedTable = table.lowercase(Locale.ROOT)
            return normalizedSchema in setOf(
                "information_schema",
                "pg_catalog",
                "mysql",
                "performance_schema",
                "sys",
            ) || normalizedTable.startsWith("sqlite_")
        }

        private fun verifyRequiredTables(dataSource: DataSource) {
            try {
                dataSource.connection.use { connection ->
                    val tables = userTables(connection)
                    if (!tables.containsAll(REQUIRED_TABLES)) {
                        throw DatabaseSchemaException(
                            "Target schema is missing tables: ${REQUIRED_TABLES - tables}",
                        )
                    }
                }
            } catch (exception: SQLException) {
                throw DatabaseReadException(exception)
            }
        }

        private fun verifyWriteAndRead(dataSource: DataSource) {
            val id = UUID.randomUUID().toString()
            val appId = "database-probe-${UUID.randomUUID()}"
            val now = Instant.now().toString()
            try {
                dataSource.connection.use { connection ->
                    val originalAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        connection.prepareStatement(
                            """
                            INSERT INTO bots (
                                id, display_name, app_id, environment,
                                app_secret_ciphertext, app_secret_key_id,
                                intents, shard_index, shard_count, enabled, revision,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, 'SANDBOX', ?, ?, 0, 0, 1, 0, 1, ?, ?)
                            """.trimIndent(),
                        ).use { insert ->
                            insert.setString(1, id)
                            insert.setString(2, "Database verification probe")
                            insert.setString(3, appId)
                            insert.setString(4, "probe-ciphertext")
                            insert.setString(5, "probe-key")
                            insert.setString(6, now)
                            insert.setString(7, now)
                            if (insert.executeUpdate() != 1) {
                                throw DatabaseWriteException("Database probe insert did not affect one row")
                            }
                        }
                        connection.prepareStatement("SELECT display_name FROM bots WHERE id = ?").use { query ->
                            query.setString(1, id)
                            query.executeQuery().use { result ->
                                if (!result.next() || result.getString(1) != "Database verification probe") {
                                    throw DatabaseReadException("Database probe could not be read back")
                                }
                            }
                        }
                    } finally {
                        connection.rollback()
                        connection.autoCommit = originalAutoCommit
                    }
                }
            } catch (exception: DatabaseReadException) {
                throw exception
            } catch (exception: DatabaseWriteException) {
                throw exception
            } catch (exception: SQLException) {
                throw DatabaseWriteException(exception)
            }
        }

        private fun count(dataSource: DataSource, table: String): Long = try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                        if (!result.next()) throw DatabaseReadException("Count query returned no row for $table")
                        result.getLong(1)
                    }
                }
            }
        } catch (exception: SQLException) {
            throw DatabaseReadException(exception)
        }

        private fun migrationDirectory(type: DatabaseType): String = when (type) {
            DatabaseType.SQLITE -> "sqlite"
            DatabaseType.MYSQL -> "mysql"
            DatabaseType.POSTGRESQL -> "postgresql"
        }

        private fun elapsedMillis(started: Long): Long = maxOf(0L, (System.nanoTime() - started) / 1_000_000L)
    }

    class DatabaseConnectionException(cause: Throwable) :
        RuntimeException("Unable to connect to target database", cause)

    class DatabaseReadException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(cause: Throwable) : super("Unable to read from target database", cause)
    }

    class DatabaseWriteException : RuntimeException {
        constructor(message: String) : super(message)
        constructor(cause: Throwable) : super("Unable to write to target database", cause)
    }

    class DatabaseSchemaException(message: String) : RuntimeException(message)
}
