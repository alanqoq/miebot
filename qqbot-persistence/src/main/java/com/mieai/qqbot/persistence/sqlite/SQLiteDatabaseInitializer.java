package com.mieai.qqbot.persistence.sqlite;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Runs the versioned SQLite migrations packaged with this module. */
public final class SQLiteDatabaseInitializer {
    private static final String MIGRATION_LOCATION = "classpath:db/migration/sqlite";

    private SQLiteDatabaseInitializer() {
    }

    public static int migrate(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate()
                .migrationsExecuted;
    }
}
