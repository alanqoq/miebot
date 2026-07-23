package com.mieai.qqbot.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwitchableDataSourceTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void waitsForBorrowedConnectionsBeforeChangingDelegates() throws Exception {
        DataSource first = database("first.db", "first");
        DataSource second = database("second.db", "second");
        SwitchableDataSource switchable = new SwitchableDataSource(first);

        try (Connection borrowed = switchable.getConnection()) {
            CompletableFuture<DataSource> swapped = CompletableFuture.supplyAsync(() -> switchable.swap(second));

            Thread.sleep(100L);
            assertThat(swapped).isNotDone();

            borrowed.close();
            assertThat(swapped.get(5, TimeUnit.SECONDS)).isSameAs(first);
        }

        try (Connection connection = switchable.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT value FROM marker")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("second");
        } finally {
            switchable.close();
        }
    }

    @Test
    void connectionCanBeReturnedByAnotherThreadWhileSwapIsWaiting() throws Exception {
        DataSource first = database("cross-thread-first.db", "first");
        DataSource second = database("cross-thread-second.db", "second");
        SwitchableDataSource switchable = new SwitchableDataSource(first);
        Connection borrowed = switchable.getConnection();

        CompletableFuture<DataSource> swapped = CompletableFuture.supplyAsync(() -> switchable.swap(second));
        Thread.sleep(100L);
        assertThat(swapped).isNotDone();

        CompletableFuture.runAsync(() -> close(borrowed)).get(5, TimeUnit.SECONDS);
        assertThat(swapped.get(5, TimeUnit.SECONDS)).isSameAs(first);
        switchable.close();
    }

    @Test
    void doesNotExposeCandidateDelegateUntilSwapIsCommitted() throws Exception {
        DataSource first = database("atomic-first.db", "first");
        DataSource second = database("atomic-second.db", "second");
        SwitchableDataSource switchable = new SwitchableDataSource(first);

        CompletableFuture<String> observed;
        try (SwitchableDataSource.Swap swap = switchable.beginSwap(second)) {
            observed = CompletableFuture.supplyAsync(() -> marker(switchable));
            Thread.sleep(100L);
            assertThat(observed).isNotDone();
        }

        assertThat(observed.get(5, TimeUnit.SECONDS)).isEqualTo("first");
        switchable.close();
    }

    private DataSource database(String file, String value) throws Exception {
        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve(file));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (value TEXT NOT NULL)");
            statement.execute("INSERT INTO marker (value) VALUES ('" + value + "')");
        }
        return dataSource;
    }

    private static String marker(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT value FROM marker")) {
            if (!result.next()) {
                throw new AssertionError("marker row is missing");
            }
            return result.getString(1);
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }
}
