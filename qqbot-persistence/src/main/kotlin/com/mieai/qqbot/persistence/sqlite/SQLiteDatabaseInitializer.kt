package com.mieai.qqbot.persistence.sqlite

import javax.sql.DataSource
import org.flywaydb.core.Flyway

/** Runs the versioned SQLite migrations packaged with this module. */
object SQLiteDatabaseInitializer {
    private const val MIGRATION_LOCATION = "classpath:db/migration/sqlite"

    fun migrate(dataSource: DataSource): Int {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .load()
            .migrate()
            .migrationsExecuted
    }
}
