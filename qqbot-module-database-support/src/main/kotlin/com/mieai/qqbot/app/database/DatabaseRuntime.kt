package com.mieai.qqbot.app.database

import com.mieai.qqbot.admin.database.DatabaseAdministrationException
import com.mieai.qqbot.admin.database.DatabaseAdministrationService
import com.mieai.qqbot.admin.database.DatabaseConfigurationView
import com.mieai.qqbot.admin.database.DatabasePassword
import com.mieai.qqbot.admin.database.DatabaseSettings
import com.mieai.qqbot.admin.database.DatabaseSwitchResult
import com.mieai.qqbot.admin.database.DatabaseTestResult
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.persistence.admin.AdminUser
import com.mieai.qqbot.persistence.admin.JdbcAdminUserRepository
import com.mieai.qqbot.runtime.security.KeyUnavailableException
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.Arrays
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import org.flywaydb.core.api.FlywayException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus

class DatabaseRuntime(
    bootstrapProperties: DatabaseBootstrapProperties,
    private val candidateFactory: DatabaseCandidateFactory,
    private val configurationStore: DatabaseConfigurationStore,
    private val clock: Clock = Clock.systemUTC(),
    beforeActiveDatabaseChanged: (() -> Unit)? = null,
    private val activeDatabaseChanged: () -> Unit = {},
) : DatabaseAdministrationService, AutoCloseable {
    private val dataSource: SwitchableDataSource
    private val switchLock = ReentrantLock()
    private val stateMonitor = Any()
    private val fenceBeforeActivation = beforeActiveDatabaseChanged != null
    private val beforeActiveDatabaseChanged = beforeActiveDatabaseChanged ?: {}
    private var state: ActiveState
    private var sqliteInstanceLock: SQLiteInstanceLock

    init {
        val loaded = configurationStore.loadPersisted() ?: run {
            DatabaseConfigurationStore.LoadedConfiguration(
                configurationStore.fromBootstrap(bootstrapProperties),
                1L,
                null,
            )
        }
        val initialProfile = loaded.profile
        val initialLock = try {
            lockFor(initialProfile)
        } catch (exception: RuntimeException) {
            initialProfile.close()
            throw exception
        }
        try {
            val candidate = prepareCandidate(initialProfile)
            try {
                dataSource = SwitchableDataSource(candidate.transferDataSource())
                sqliteInstanceLock = initialLock
                state = ActiveState(
                    initialProfile,
                    loaded.revision,
                    candidate.product,
                    candidate.version,
                    loaded.lastSwitchedAt,
                )
            } finally {
                candidate.close()
            }
        } catch (exception: RuntimeException) {
            initialLock.close()
            initialProfile.close()
            throw exception
        }
    }

    val activeDataSource: DataSource
        get() = dataSource

    override fun current(): DatabaseConfigurationView = synchronized(stateMonitor) {
        view(state, switchLock.isLocked)
    }

    override fun test(settings: DatabaseSettings): DatabaseTestResult {
        val profile = resolve(settings)
        profile.use {
            prepareCandidate(profile).use { candidate ->
                return result(candidate, profile.type, false)
            }
        }
    }

    override fun switchDatabase(
        expectedRevision: Long,
        settings: DatabaseSettings,
        currentAdminUsername: String,
    ): DatabaseSwitchResult = switchTo(expectedRevision, resolve(settings), currentAdminUsername)

    override fun reload(expectedRevision: Long, currentAdminUsername: String): DatabaseSwitchResult {
        val loaded = try {
            configurationStore.loadCandidateRequired()
        } catch (exception: RuntimeException) {
            throw administrationFailure(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DATABASE_CONFIG_INVALID",
                "The candidate database configuration file could not be loaded",
                exception,
            )
        }
        return switchTo(expectedRevision, loaded.profile, currentAdminUsername)
    }

    override fun close() {
        try {
            dataSource.close()
        } finally {
            synchronized(stateMonitor) {
                state.profile.close()
                sqliteInstanceLock.close()
            }
        }
    }

    private fun switchTo(
        expectedRevision: Long,
        profile: DatabaseProfile,
        currentAdminUsername: String,
    ): DatabaseSwitchResult {
        if (!switchLock.tryLock()) {
            profile.close()
            throw DatabaseAdministrationException(
                HttpStatus.CONFLICT,
                "DATABASE_SWITCH_IN_PROGRESS",
                "Another database switch is already in progress",
            )
        }

        try {
            val previousState = synchronized(stateMonitor) {
                state.also { current ->
                    if (current.revision != expectedRevision) {
                        throw DatabaseAdministrationException(
                            HttpStatus.CONFLICT,
                            "DATABASE_CONFIG_CONFLICT",
                            "Database configuration revision has changed",
                        )
                    }
                }
            }
            val currentAdmin = JdbcAdminUserRepository(dataSource)
                .findByUsername(currentAdminUsername)
                ?: throw DatabaseAdministrationException(
                    HttpStatus.CONFLICT,
                    "CURRENT_ADMIN_MISSING",
                    "The active administrator could not be loaded",
                )

            val reuseSqliteLock = sameSQLitePath(previousState.profile, profile)
            val nextSqliteLock = if (reuseSqliteLock) sqliteInstanceLock else lockFor(profile)
            var activated = false
            try {
                prepareCandidate(profile).use { candidate ->
                    val adminSeeded = ensureAdministrator(candidate.dataSource, currentAdmin)
                    val nextRevision = Math.addExact(previousState.revision, 1L)
                    val switchedAt = clock.instant()
                    val verification = result(candidate, profile.type, adminSeeded)

                    configurationStore.prepare(profile, nextRevision, switchedAt).use { prepared ->
                        val nextDelegate = candidate.transferDataSource()
                        val nextState = ActiveState(
                            profile,
                            nextRevision,
                            candidate.product,
                            candidate.version,
                            switchedAt,
                        )
                        var fenced = false
                        val previousSqliteLock = sqliteInstanceLock
                        try {
                            if (fenceBeforeActivation) {
                                beforeActiveDatabaseChanged()
                                fenced = true
                            }
                            val previousDelegate = dataSource.beginSwap(nextDelegate).use { swap ->
                                val previous = swap.previousDelegate
                                verifyDataSource(nextDelegate)
                                configurationStore.commit(prepared)
                                synchronized(stateMonitor) {
                                    state = nextState
                                    sqliteInstanceLock = nextSqliteLock
                                }
                                swap.commit()
                                activated = true
                                previous
                            }

                            previousState.profile.close()
                            SwitchableDataSource.closeDataSource(previousDelegate)
                            if (previousSqliteLock !== nextSqliteLock) previousSqliteLock.close()
                            notifyActiveDatabaseChanged()
                            return DatabaseSwitchResult(view(nextState, false), verification)
                        } finally {
                            if (fenced && !activated) notifyActiveDatabaseChanged()
                            if (!activated) SwitchableDataSource.closeDataSource(nextDelegate)
                        }
                    }
                }
            } finally {
                if (!activated && !reuseSqliteLock) nextSqliteLock.close()
            }
        } catch (exception: DatabaseAdministrationException) {
            profile.close()
            throw exception
        } catch (exception: RuntimeException) {
            profile.close()
            throw mapFailure(exception)
        } finally {
            switchLock.unlock()
        }
    }

    private fun notifyActiveDatabaseChanged() {
        try {
            activeDatabaseChanged()
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Unable to notify bot runtimes after database activation ({})",
                exception.javaClass.simpleName,
            )
        }
    }

    private fun ensureAdministrator(candidateDataSource: DataSource, currentAdmin: AdminUser): Boolean {
        val targetRepository = JdbcAdminUserRepository(candidateDataSource)
        if (targetRepository.findByUsername(currentAdmin.username) != null) return false
        if (!candidateFactory.isUnwritten(candidateDataSource)) {
            throw DatabaseAdministrationException(
                HttpStatus.CONFLICT,
                "TARGET_ADMIN_MISSING",
                "The target database does not contain the current administrator",
            )
        }
        if (
            !targetRepository.insertFirst(currentAdmin) &&
            targetRepository.findByUsername(currentAdmin.username) == null
        ) {
            throw DatabaseAdministrationException(
                HttpStatus.CONFLICT,
                "TARGET_ADMIN_MISSING",
                "The target administrator could not be initialized",
            )
        }
        return true
    }

    private fun resolve(settings: DatabaseSettings): DatabaseProfile {
        if (settings.type == DatabaseType.SQLITE) return DatabaseProfile.fromSettings(settings, null)

        var password = suppliedPassword(settings.password)
        if (password == null) {
            synchronized(stateMonitor) {
                if (state.profile.sameCredentialScope(settings)) password = state.profile.copyPassword()
            }
        }
        val resolvedPassword = password ?: throw DatabaseAdministrationException(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Database password is required",
            mapOf("password" to "Database password is required for this connection"),
        )
        try {
            return DatabaseProfile.fromSettings(settings, resolvedPassword)
        } finally {
            Arrays.fill(resolvedPassword, '\u0000')
        }
    }

    private fun prepareCandidate(profile: DatabaseProfile): DatabaseCandidate = try {
        candidateFactory.prepare(profile)
    } catch (exception: RuntimeException) {
        throw mapFailure(exception)
    }

    private fun result(
        candidate: DatabaseCandidate,
        type: DatabaseType,
        adminSeeded: Boolean,
    ): DatabaseTestResult = DatabaseTestResult(
        true,
        type,
        candidate.product,
        candidate.version,
        candidate.latencyMs,
        true,
        true,
        candidate.schemaState,
        candidate.schemaVersion,
        candidate.initialized,
        adminSeeded,
        candidate.botCount,
        clock.instant(),
    )

    private data class ActiveState(
        val profile: DatabaseProfile,
        val revision: Long,
        val product: String,
        val version: String,
        val lastSwitchedAt: Instant?,
    )

    companion object {
        private val LOGGER = LoggerFactory.getLogger(DatabaseRuntime::class.java)

        private fun suppliedPassword(password: DatabasePassword?): CharArray? = password?.copyValue()

        private fun lockFor(profile: DatabaseProfile): SQLiteInstanceLock = SQLiteInstanceLock.acquire(
            if (profile.type == DatabaseType.SQLITE) profile.sqlitePath else null,
        )

        private fun sameSQLitePath(first: DatabaseProfile, second: DatabaseProfile): Boolean =
            first.type == DatabaseType.SQLITE &&
                second.type == DatabaseType.SQLITE &&
                first.sqlitePath == second.sqlitePath

        private fun mapFailure(exception: RuntimeException): DatabaseAdministrationException {
            if (exception is DatabaseAdministrationException) return exception
            if (findCause(exception, DatabaseCandidateFactory.DatabaseConnectionException::class.java) != null) {
                return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_CONNECTION_FAILED",
                    "The database connection could not be established",
                    exception,
                )
            }
            if (findCause(exception, DatabaseCandidateFactory.DatabaseReadException::class.java) != null) {
                return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_READ_FAILED",
                    "The database could not be read",
                    exception,
                )
            }
            if (findCause(exception, DatabaseCandidateFactory.DatabaseWriteException::class.java) != null) {
                return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_WRITE_FAILED",
                    "The database write verification failed",
                    exception,
                )
            }
            if (
                findCause(exception, DatabaseCandidateFactory.DatabaseSchemaException::class.java) != null ||
                findCause(exception, FlywayException::class.java) != null
            ) {
                return administrationFailure(
                    HttpStatus.CONFLICT,
                    "TARGET_SCHEMA_INCOMPATIBLE",
                    "The target database schema is not compatible",
                    exception,
                )
            }
            if (findCause(exception, KeyUnavailableException::class.java) != null) {
                return administrationFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "APP_SECRET_KEY_UNAVAILABLE",
                    "The master key required to save the database password is not configured",
                    exception,
                )
            }
            if (findCause(exception, SQLException::class.java) != null) {
                return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_CONNECTION_FAILED",
                    "The database connection could not be established",
                    exception,
                )
            }
            return administrationFailure(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_SWITCH_FAILED",
                "The database operation could not be completed",
                exception,
            )
        }

        private fun administrationFailure(
            status: HttpStatus,
            code: String,
            message: String,
            @Suppress("UNUSED_PARAMETER") ignored: Throwable,
        ): DatabaseAdministrationException = DatabaseAdministrationException(status, code, message)

        private fun <T : Throwable> findCause(exception: Throwable, type: Class<T>): T? {
            var current: Throwable? = exception
            while (current != null) {
                if (type.isInstance(current)) return type.cast(current)
                current = current.cause
            }
            return null
        }

        private fun verifyDataSource(candidateDataSource: DataSource) {
            try {
                candidateDataSource.connection.use { connection ->
                    if (!connection.isValid(5)) throw SQLException("active datasource validation failed")
                }
            } catch (exception: SQLException) {
                throw DatabaseCandidateFactory.DatabaseConnectionException(exception)
            }
        }

        private fun view(active: ActiveState, switching: Boolean): DatabaseConfigurationView {
            val profile = active.profile
            return DatabaseConfigurationView(
                active.revision,
                profile.type,
                profile.sqlitePath?.toString(),
                profile.busyTimeout?.toMillis(),
                profile.host,
                if (profile.type == DatabaseType.SQLITE) null else profile.port,
                profile.databaseName,
                profile.username,
                profile.sslMode,
                profile.connectTimeout?.toMillis(),
                profile.hasPassword(),
                active.product,
                active.version,
                switching,
                active.lastSwitchedAt,
            )
        }
    }
}
