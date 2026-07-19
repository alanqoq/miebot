package com.mieai.qqbot.persistence.sqlite;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** Creates SQLite data sources with the safety settings required by the runtime. */
public final class SQLiteDataSourceFactory {
    public static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(5);

    private SQLiteDataSourceFactory() {
    }

    public static DataSource create(Path databaseFile) {
        return create(databaseFile, DEFAULT_BUSY_TIMEOUT);
    }

    public static DataSource create(Path databaseFile, Duration busyTimeout) {
        Objects.requireNonNull(databaseFile, "databaseFile must not be null");
        Objects.requireNonNull(busyTimeout, "busyTimeout must not be null");
        long timeoutMillis = busyTimeout.toMillis();
        if (timeoutMillis < 1L || timeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("busyTimeout must be between 1 ms and "
                    + Integer.MAX_VALUE + " ms");
        }

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(Math.toIntExact(timeoutMillis));
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath().normalize());
        return dataSource;
    }
}
