package com.mieai.qqbot.app.database

import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class SwitchableDataSourceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `waits for borrowed connections before changing delegates`() {
        val first = database("first.db", "first")
        val second = database("second.db", "second")
        val switchable = SwitchableDataSource(first)

        switchable.connection.use { borrowed ->
            val swapped = CompletableFuture.supplyAsync { switchable.swap(second) }

            Thread.sleep(100L)
            assertThat(swapped).isNotDone()

            borrowed.close()
            assertThat(swapped.get(5, TimeUnit.SECONDS)).isSameAs(first)
        }

        try {
            switchable.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT value FROM marker").use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString(1)).isEqualTo("second")
                    }
                }
            }
        } finally {
            switchable.close()
        }
    }

    @Test
    fun `connection can be returned by another thread while swap is waiting`() {
        val first = database("cross-thread-first.db", "first")
        val second = database("cross-thread-second.db", "second")
        val switchable = SwitchableDataSource(first)
        val borrowed = switchable.connection

        val swapped = CompletableFuture.supplyAsync { switchable.swap(second) }
        Thread.sleep(100L)
        assertThat(swapped).isNotDone()

        CompletableFuture.runAsync { close(borrowed) }.get(5, TimeUnit.SECONDS)
        assertThat(swapped.get(5, TimeUnit.SECONDS)).isSameAs(first)
        switchable.close()
    }

    @Test
    fun `does not expose candidate delegate until swap is committed`() {
        val first = database("atomic-first.db", "first")
        val second = database("atomic-second.db", "second")
        val switchable = SwitchableDataSource(first)

        val observed: CompletableFuture<String>
        switchable.beginSwap(second).use {
            observed = CompletableFuture.supplyAsync { marker(switchable) }
            Thread.sleep(100L)
            assertThat(observed).isNotDone()
        }

        assertThat(observed.get(5, TimeUnit.SECONDS)).isEqualTo("first")
        switchable.close()
    }

    private fun database(file: String, value: String): DataSource {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve(file))
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE marker (value TEXT NOT NULL)")
                statement.execute("INSERT INTO marker (value) VALUES ('$value')")
            }
        }
        return dataSource
    }

    private fun marker(dataSource: DataSource): String = try {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM marker").use { result ->
                    if (!result.next()) throw AssertionError("marker row is missing")
                    result.getString(1)
                }
            }
        }
    } catch (exception: Exception) {
        throw CompletionException(exception)
    }

    private fun close(connection: Connection) {
        try {
            connection.close()
        } catch (exception: Exception) {
            throw CompletionException(exception)
        }
    }
}
