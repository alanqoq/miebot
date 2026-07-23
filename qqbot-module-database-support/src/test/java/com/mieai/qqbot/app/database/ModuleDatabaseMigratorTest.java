package com.mieai.qqbot.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.module.host.ModuleArtifactRegistry;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDatabaseMigratorTest {
    @TempDir
    private Path directory;

    @Test
    void runsNamespacedMigrationsWithAnIndependentHistoryTable() throws Exception {
        Path modules = java.nio.file.Files.createDirectory(directory.resolve("modules"));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/qqbot/module.json", """
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
                """);
        entries.put(
                "META-INF/qqbot/modules/reports/db/sqlite/V001__create_reports.sql",
                "CREATE TABLE module_reports (id INTEGER PRIMARY KEY, value TEXT NOT NULL);");
        writeJar(modules.resolve("reports.jar"), entries);

        DataSource dataSource = SQLiteDataSourceFactory.create(
                directory.resolve("module.db"), Duration.ofSeconds(5));
        try (ModuleArtifactRegistry registry = ModuleArtifactRegistry.load(modules)) {
            ModuleDatabaseMigrator migrator = new ModuleDatabaseMigrator(registry);
            assertThat(migrator.migrate(dataSource, "sqlite")).isEqualTo(1);
            assertThat(migrator.migrate(dataSource, "sqlite")).isZero();
        }

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            try (var tables = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                java.util.List<String> names = new java.util.ArrayList<>();
                while (tables.next()) names.add(tables.getString(1));
                assertThat(names).contains("module_reports");
                assertThat(names).anyMatch(name -> name.startsWith("qqbot_flyway_reports_"));
            }
        }
    }

    private static void writeJar(Path path, Map<String, String> entries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(
                java.nio.file.Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
