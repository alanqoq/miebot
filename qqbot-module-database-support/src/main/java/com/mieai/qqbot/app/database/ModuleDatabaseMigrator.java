package com.mieai.qqbot.app.database;

import com.mieai.qqbot.module.host.ModuleArtifact;
import com.mieai.qqbot.module.host.ModuleArtifactRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/** Runs collision-free migrations owned by external framework modules. */
final class ModuleDatabaseMigrator {
    private final ModuleArtifactRegistry artifacts;

    ModuleDatabaseMigrator(ModuleArtifactRegistry artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
    }

    int migrate(DataSource dataSource, String dialect) {
        int executed = 0;
        for (ModuleArtifact artifact : artifacts.startOrder()) {
            String moduleId = artifact.descriptor().id();
            String location = "META-INF/qqbot/modules/" + moduleId + "/db/" + dialect + "/";
            if (!artifacts.containsResourcePrefix(moduleId, location)) {
                continue;
            }
            executed += Flyway.configure(artifacts.artifactClassLoader(moduleId))
                    .dataSource(dataSource)
                    .locations("classpath:" + location)
                    .table(historyTable(moduleId))
                    .baselineOnMigrate(true)
                    .baselineVersion(MigrationVersion.fromVersion("0"))
                    .cleanDisabled(true)
                    .validateMigrationNaming(true)
                    .load()
                    .migrate()
                    .migrationsExecuted;
        }
        return executed;
    }

    private static String historyTable(String moduleId) {
        String normalized = moduleId.replaceAll("[^a-z0-9]", "_");
        if (normalized.length() > 40) normalized = normalized.substring(0, 40);
        return "qqbot_flyway_" + normalized + "_" + shortHash(moduleId);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
