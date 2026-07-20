package com.mieai.qqbot.app.database;

import com.mieai.qqbot.admin.database.DatabaseAdministrationException;
import com.mieai.qqbot.admin.database.DatabaseAdministrationService;
import com.mieai.qqbot.admin.database.DatabaseConfigurationView;
import com.mieai.qqbot.admin.database.DatabasePassword;
import com.mieai.qqbot.admin.database.DatabaseSettings;
import com.mieai.qqbot.admin.database.DatabaseSwitchResult;
import com.mieai.qqbot.admin.database.DatabaseTestResult;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.persistence.admin.AdminUser;
import com.mieai.qqbot.persistence.admin.JdbcAdminUserRepository;
import com.mieai.qqbot.runtime.security.KeyUnavailableException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

final class DatabaseRuntime implements DatabaseAdministrationService, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseRuntime.class);

    private final DatabaseCandidateFactory candidateFactory;
    private final DatabaseConfigurationStore configurationStore;
    private final SwitchableDataSource dataSource;
    private final ReentrantLock switchLock = new ReentrantLock();
    private final Object stateMonitor = new Object();
    private final Clock clock;
    private final Runnable beforeActiveDatabaseChanged;
    private final boolean fenceBeforeActivation;
    private final Runnable activeDatabaseChanged;

    private ActiveState state;
    private SQLiteInstanceLock sqliteInstanceLock;

    DatabaseRuntime(
            DatabaseBootstrapProperties bootstrapProperties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore) {
        this(
                bootstrapProperties,
                candidateFactory,
                configurationStore,
                Clock.systemUTC(),
                null,
                () -> {});
    }

    DatabaseRuntime(
            DatabaseBootstrapProperties bootstrapProperties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore,
            Runnable activeDatabaseChanged) {
        this(
                bootstrapProperties,
                candidateFactory,
                configurationStore,
                Clock.systemUTC(),
                null,
                activeDatabaseChanged);
    }

    DatabaseRuntime(
            DatabaseBootstrapProperties bootstrapProperties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore,
            Clock clock) {
        this(
                bootstrapProperties,
                candidateFactory,
                configurationStore,
                clock,
                null,
                () -> {});
    }

    DatabaseRuntime(
            DatabaseBootstrapProperties bootstrapProperties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore,
            Clock clock,
            Runnable activeDatabaseChanged) {
        this(
                bootstrapProperties,
                candidateFactory,
                configurationStore,
                clock,
                null,
                activeDatabaseChanged);
    }

    DatabaseRuntime(
            DatabaseBootstrapProperties bootstrapProperties,
            DatabaseCandidateFactory candidateFactory,
            DatabaseConfigurationStore configurationStore,
            Clock clock,
            Runnable beforeActiveDatabaseChanged,
            Runnable activeDatabaseChanged) {
        this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory must not be null");
        this.configurationStore = Objects.requireNonNull(
                configurationStore, "configurationStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        fenceBeforeActivation = beforeActiveDatabaseChanged != null;
        this.beforeActiveDatabaseChanged = fenceBeforeActivation
                ? beforeActiveDatabaseChanged
                : () -> {};
        this.activeDatabaseChanged = Objects.requireNonNull(
                activeDatabaseChanged, "activeDatabaseChanged must not be null");

        DatabaseConfigurationStore.LoadedConfiguration loaded = configurationStore.loadPersisted()
                .orElseGet(() -> new DatabaseConfigurationStore.LoadedConfiguration(
                        configurationStore.fromBootstrap(bootstrapProperties),
                        1L,
                        null));
        DatabaseProfile initialProfile = loaded.profile();
        SQLiteInstanceLock initialLock = lockFor(initialProfile);
        try (DatabaseCandidate candidate = prepareCandidate(initialProfile)) {
            DataSource initialDataSource = candidate.transferDataSource();
            dataSource = new SwitchableDataSource(initialDataSource);
            sqliteInstanceLock = initialLock;
            state = new ActiveState(
                    initialProfile,
                    loaded.revision(),
                    candidate.product(),
                    candidate.version(),
                    loaded.lastSwitchedAt());
        } catch (RuntimeException exception) {
            initialLock.close();
            initialProfile.close();
            throw exception;
        }
    }

    DataSource dataSource() {
        return dataSource;
    }

    boolean sqliteActive() {
        synchronized (stateMonitor) {
            return state.profile().type() == DatabaseType.SQLITE;
        }
    }

    java.nio.file.Path sqlitePath() {
        synchronized (stateMonitor) {
            return state.profile().sqlitePath();
        }
    }

    @Override
    public DatabaseConfigurationView current() {
        synchronized (stateMonitor) {
            return view(state, switchLock.isLocked());
        }
    }

    @Override
    public DatabaseTestResult test(DatabaseSettings settings) {
        DatabaseProfile profile = resolve(settings);
        try (profile; DatabaseCandidate candidate = prepareCandidate(profile)) {
            return result(candidate, profile.type(), false);
        }
    }

    @Override
    public DatabaseSwitchResult switchDatabase(
            long expectedRevision,
            DatabaseSettings settings,
            String currentAdminUsername) {
        DatabaseProfile profile = resolve(settings);
        return switchTo(expectedRevision, profile, currentAdminUsername);
    }

    @Override
    public DatabaseSwitchResult reload(long expectedRevision, String currentAdminUsername) {
        DatabaseConfigurationStore.LoadedConfiguration loaded;
        try {
            loaded = configurationStore.loadCandidateRequired();
        } catch (RuntimeException exception) {
            throw administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_CONFIG_INVALID",
                    "The candidate database configuration file could not be loaded",
                    exception);
        }
        return switchTo(expectedRevision, loaded.profile(), currentAdminUsername);
    }

    @Override
    public void close() {
        try {
            dataSource.close();
        } finally {
            synchronized (stateMonitor) {
                state.profile().close();
                sqliteInstanceLock.close();
            }
        }
    }

    private DatabaseSwitchResult switchTo(
            long expectedRevision,
            DatabaseProfile profile,
            String currentAdminUsername) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(currentAdminUsername, "currentAdminUsername must not be null");
        if (!switchLock.tryLock()) {
            profile.close();
            throw new DatabaseAdministrationException(
                    HttpStatus.CONFLICT,
                    "DATABASE_SWITCH_IN_PROGRESS",
                    "Another database switch is already in progress");
        }

        try {
            ActiveState previousState;
            synchronized (stateMonitor) {
                previousState = state;
                if (previousState.revision() != expectedRevision) {
                    throw new DatabaseAdministrationException(
                            HttpStatus.CONFLICT,
                            "DATABASE_CONFIG_CONFLICT",
                            "Database configuration revision has changed");
                }
            }

            AdminUser currentAdmin = new JdbcAdminUserRepository(dataSource)
                    .findByUsername(currentAdminUsername)
                    .orElseThrow(() -> new DatabaseAdministrationException(
                            HttpStatus.CONFLICT,
                            "CURRENT_ADMIN_MISSING",
                            "The active administrator could not be loaded"));

            boolean reuseSqliteLock = sameSQLitePath(previousState.profile(), profile);
            SQLiteInstanceLock nextSqliteLock = reuseSqliteLock
                    ? sqliteInstanceLock : lockFor(profile);
            boolean activated = false;
            try (DatabaseCandidate candidate = prepareCandidate(profile)) {
                boolean adminSeeded = ensureAdministrator(candidate.dataSource(), currentAdmin);
                long nextRevision = Math.addExact(previousState.revision(), 1L);
                Instant switchedAt = clock.instant();
                DatabaseTestResult verification = result(candidate, profile.type(), adminSeeded);

                try (DatabaseConfigurationStore.PreparedConfiguration prepared =
                        configurationStore.prepare(profile, nextRevision, switchedAt)) {
                    DataSource nextDelegate = candidate.transferDataSource();
                    ActiveState nextState = new ActiveState(
                            profile,
                            nextRevision,
                            candidate.product(),
                            candidate.version(),
                            switchedAt);
                    DataSource previousDelegate;
                    boolean fenced = false;
                    SQLiteInstanceLock previousSqliteLock = sqliteInstanceLock;
                    try {
                        if (fenceBeforeActivation) {
                            beforeActiveDatabaseChanged.run();
                            fenced = true;
                        }
                        try (SwitchableDataSource.Swap swap = dataSource.beginSwap(nextDelegate)) {
                            previousDelegate = swap.previousDelegate();
                            verifyDataSource(nextDelegate);
                            configurationStore.commit(prepared);
                            synchronized (stateMonitor) {
                                state = nextState;
                                sqliteInstanceLock = nextSqliteLock;
                            }
                            swap.commit();
                            activated = true;
                        }

                        previousState.profile().close();
                        SwitchableDataSource.closeDataSource(previousDelegate);
                        if (previousSqliteLock != nextSqliteLock) previousSqliteLock.close();
                        notifyActiveDatabaseChanged();
                        return new DatabaseSwitchResult(view(nextState, false), verification);
                    } finally {
                        if (fenced && !activated) {
                            // The old topology was fenced before activation. Reconcile it against
                            // the still-active old delegate when candidate commit/verification fails.
                            notifyActiveDatabaseChanged();
                        }
                        if (!activated) {
                            SwitchableDataSource.closeDataSource(nextDelegate);
                        }
                    }
                }
            } finally {
                if (!activated && !reuseSqliteLock) nextSqliteLock.close();
            }
        } catch (DatabaseAdministrationException exception) {
            profile.close();
            throw exception;
        } catch (RuntimeException exception) {
            profile.close();
            throw mapFailure(exception);
        } finally {
            switchLock.unlock();
        }
    }

    private void notifyActiveDatabaseChanged() {
        try {
            activeDatabaseChanged.run();
        } catch (RuntimeException exception) {
            // The database is already committed. Periodic reconciliation remains the fallback.
            LOGGER.warn(
                    "Unable to notify bot runtimes after database activation ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private boolean ensureAdministrator(DataSource candidateDataSource, AdminUser currentAdmin) {
        JdbcAdminUserRepository targetRepository = new JdbcAdminUserRepository(candidateDataSource);
        if (targetRepository.findByUsername(currentAdmin.username()).isPresent()) {
            return false;
        }
        if (!candidateFactory.isUnwritten(candidateDataSource)) {
            throw new DatabaseAdministrationException(
                    HttpStatus.CONFLICT,
                    "TARGET_ADMIN_MISSING",
                    "The target database does not contain the current administrator");
        }
        if (!targetRepository.insertFirst(currentAdmin)
                && targetRepository.findByUsername(currentAdmin.username()).isEmpty()) {
            throw new DatabaseAdministrationException(
                    HttpStatus.CONFLICT,
                    "TARGET_ADMIN_MISSING",
                    "The target administrator could not be initialized");
        }
        return true;
    }

    private DatabaseProfile resolve(DatabaseSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        if (settings.type() == DatabaseType.SQLITE) {
            return DatabaseProfile.fromSettings(settings, null);
        }

        char[] password = suppliedPassword(settings.password());
        if (password == null) {
            synchronized (stateMonitor) {
                if (state.profile().sameCredentialScope(settings)) {
                    password = state.profile().copyPassword();
                }
            }
        }
        if (password == null) {
            throw new DatabaseAdministrationException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Database password is required",
                    Map.of("password", "Database password is required for this connection"));
        }
        try {
            return DatabaseProfile.fromSettings(settings, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] suppliedPassword(DatabasePassword password) {
        return password == null ? null : password.copyValue();
    }

    private DatabaseCandidate prepareCandidate(DatabaseProfile profile) {
        try {
            return candidateFactory.prepare(profile);
        } catch (RuntimeException exception) {
            throw mapFailure(exception);
        }
    }

    private static SQLiteInstanceLock lockFor(DatabaseProfile profile) {
        return SQLiteInstanceLock.acquire(profile.type() == DatabaseType.SQLITE
                ? profile.sqlitePath() : null);
    }

    private static boolean sameSQLitePath(DatabaseProfile first, DatabaseProfile second) {
        return first.type() == DatabaseType.SQLITE
                && second.type() == DatabaseType.SQLITE
                && first.sqlitePath().equals(second.sqlitePath());
    }

    private static DatabaseAdministrationException mapFailure(RuntimeException exception) {
        if (exception instanceof DatabaseAdministrationException administrationException) {
            return administrationException;
        }
        if (findCause(exception, DatabaseCandidateFactory.DatabaseConnectionException.class) != null) {
            return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_CONNECTION_FAILED",
                    "The database connection could not be established",
                    exception);
        }
        if (findCause(exception, DatabaseCandidateFactory.DatabaseReadException.class) != null) {
            return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_READ_FAILED",
                    "The database could not be read",
                    exception);
        }
        if (findCause(exception, DatabaseCandidateFactory.DatabaseWriteException.class) != null) {
            return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_WRITE_FAILED",
                    "The database write verification failed",
                    exception);
        }
        if (findCause(exception, DatabaseCandidateFactory.DatabaseSchemaException.class) != null
                || findCause(exception, FlywayException.class) != null) {
            return administrationFailure(
                    HttpStatus.CONFLICT,
                    "TARGET_SCHEMA_INCOMPATIBLE",
                    "The target database schema is not compatible",
                    exception);
        }
        if (findCause(exception, KeyUnavailableException.class) != null) {
            return administrationFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "APP_SECRET_KEY_UNAVAILABLE",
                    "The master key required to save the database password is not configured",
                    exception);
        }
        if (findCause(exception, SQLException.class) != null) {
            return administrationFailure(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATABASE_CONNECTION_FAILED",
                    "The database connection could not be established",
                    exception);
        }
        return administrationFailure(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_SWITCH_FAILED",
                "The database operation could not be completed",
                exception);
    }

    private static DatabaseAdministrationException administrationFailure(
            HttpStatus status,
            String code,
            String message,
            Throwable ignored) {
        return new DatabaseAdministrationException(status, code, message);
    }

    private static <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static void verifyDataSource(DataSource candidateDataSource) {
        try (Connection connection = candidateDataSource.getConnection()) {
            if (!connection.isValid(5)) {
                throw new SQLException("active datasource validation failed");
            }
        } catch (SQLException exception) {
            throw new DatabaseCandidateFactory.DatabaseConnectionException(exception);
        }
    }

    private DatabaseTestResult result(DatabaseCandidate candidate, DatabaseType type, boolean adminSeeded) {
        return new DatabaseTestResult(
                true,
                type,
                candidate.product(),
                candidate.version(),
                candidate.latencyMs(),
                true,
                true,
                candidate.schemaState(),
                candidate.schemaVersion(),
                candidate.initialized(),
                adminSeeded,
                candidate.botCount(),
                clock.instant());
    }

    private static DatabaseConfigurationView view(ActiveState active, boolean switching) {
        DatabaseProfile profile = active.profile();
        return new DatabaseConfigurationView(
                active.revision(),
                profile.type(),
                profile.sqlitePath() == null ? null : profile.sqlitePath().toString(),
                profile.busyTimeout() == null ? null : profile.busyTimeout().toMillis(),
                profile.host(),
                profile.type() == DatabaseType.SQLITE ? null : profile.port(),
                profile.databaseName(),
                profile.username(),
                profile.sslMode(),
                profile.connectTimeout() == null ? null : profile.connectTimeout().toMillis(),
                profile.hasPassword(),
                active.product(),
                active.version(),
                switching,
                active.lastSwitchedAt());
    }

    private record ActiveState(
            DatabaseProfile profile,
            long revision,
            String product,
            String version,
            Instant lastSwitchedAt) {
        ActiveState {
            Objects.requireNonNull(profile, "profile must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(version, "version must not be null");
        }
    }
}
