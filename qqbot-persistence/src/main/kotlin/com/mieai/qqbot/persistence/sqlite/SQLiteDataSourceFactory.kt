package com.mieai.qqbot.persistence.sqlite

import java.nio.file.Path
import java.time.Duration
import javax.sql.DataSource
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/** Creates SQLite data sources with the safety settings required by the runtime. */
object SQLiteDataSourceFactory {
    val DEFAULT_BUSY_TIMEOUT: Duration = Duration.ofSeconds(5)

    fun create(databaseFile: Path): DataSource = create(databaseFile, DEFAULT_BUSY_TIMEOUT)

    fun create(databaseFile: Path, busyTimeout: Duration): DataSource {
        val timeoutMillis = busyTimeout.toMillis()
        require(timeoutMillis in 1L..Int.MAX_VALUE.toLong()) {
            "busyTimeout must be between 1 ms and ${Int.MAX_VALUE} ms"
        }

        val config = SQLiteConfig()
        config.enforceForeignKeys(true)
        config.setBusyTimeout(Math.toIntExact(timeoutMillis))
        config.setJournalMode(SQLiteConfig.JournalMode.WAL)

        val dataSource = SQLiteDataSource(config)
        dataSource.url = "jdbc:sqlite:${databaseFile.toAbsolutePath().normalize()}"
        return dataSource
    }
}
