package com.mieai.qqbot.app.database

import com.mieai.qqbot.module.host.ModuleArtifactRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion

/** Runs collision-free migrations owned by external framework modules. */
class ModuleDatabaseMigrator(
    private val artifacts: ModuleArtifactRegistry,
) {
    fun migrate(dataSource: DataSource, dialect: String): Int {
        var executed = 0
        for (artifact in artifacts.startOrder()) {
            val moduleId = artifact.descriptor.id
            val location = "META-INF/qqbot/modules/$moduleId/db/$dialect/"
            if (!artifacts.containsResourcePrefix(moduleId, location)) continue
            executed += Flyway.configure(artifacts.artifactClassLoader(moduleId))
                .dataSource(dataSource)
                .locations("classpath:$location")
                .table(historyTable(moduleId))
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
                .migrationsExecuted
        }
        return executed
    }

    private companion object {
        fun historyTable(moduleId: String): String {
            var normalized = moduleId.replace(Regex("[^a-z0-9]"), "_")
            if (normalized.length > 40) normalized = normalized.take(40)
            return "qqbot_flyway_${normalized}_${shortHash(moduleId)}"
        }

        fun shortHash(value: String): String = try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            HexFormat.of().formatHex(digest, 0, 4)
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", exception)
        }
    }
}
