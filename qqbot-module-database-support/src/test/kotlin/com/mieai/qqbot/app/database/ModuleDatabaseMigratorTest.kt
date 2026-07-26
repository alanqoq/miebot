package com.mieai.qqbot.app.database

import com.mieai.qqbot.module.host.ModuleArtifactRegistry
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ModuleDatabaseMigratorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `runs namespaced migrations with an independent history table`() {
        val modules = Files.createDirectory(directory.resolve("modules"))
        val entries = linkedMapOf(
            "META-INF/qqbot/module.json" to
                """
                {
                  "schemaVersion": 1,
                  "id": "reports",
                  "name": "Reports",
                  "version": "1.0.0",
                  "minimumFrameworkVersion": "1.0.0",
                  "dependencies": [],
                  "capabilities": [],
                  "web": { "pages": [] }
                }
                """.trimIndent(),
            "META-INF/qqbot/modules/reports/db/sqlite/V001__create_reports.sql" to
                "CREATE TABLE module_reports (id INTEGER PRIMARY KEY, value TEXT NOT NULL);",
        )
        writeJar(modules.resolve("reports.jar"), entries)

        val dataSource = SQLiteDataSourceFactory.create(directory.resolve("module.db"), Duration.ofSeconds(5))
        ModuleArtifactRegistry.load(modules).use { registry ->
            val migrator = ModuleDatabaseMigrator(registry)
            assertThat(migrator.migrate(dataSource, "sqlite")).isEqualTo(1)
            assertThat(migrator.migrate(dataSource, "sqlite")).isZero()
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                ).use { tables ->
                    val names = buildList {
                        while (tables.next()) add(tables.getString(1))
                    }
                    assertThat(names).contains("module_reports")
                    assertThat(names).anyMatch { name -> name.startsWith("qqbot_flyway_reports_") }
                }
            }
        }
    }

    private fun writeJar(path: Path, entries: Map<String, String>) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(JarEntry(name))
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
    }
}
