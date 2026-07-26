package com.mieai.qqbot.app.database

import com.fasterxml.jackson.databind.json.JsonMapper
import com.mieai.qqbot.admin.database.DatabaseSslMode
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import com.mieai.qqbot.runtime.security.StaticKeyProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays

class DatabaseConfigurationStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `resolves password file relative to candidate configuration`() {
        val activeDirectory = Files.createDirectory(temporaryDirectory.resolve("active"))
        val candidateDirectory = Files.createDirectory(temporaryDirectory.resolve("candidate"))
        val activeFile = activeDirectory.resolve("database.json")
        val candidateFile = candidateDirectory.resolve("database.json.candidate")
        Files.writeString(
            candidateDirectory.resolve("db-password.txt"),
            "candidate-secret\n",
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            candidateFile,
            """
            {
              "type": "MYSQL",
              "host": "database.internal",
              "port": 3306,
              "databaseName": "qqbot",
              "username": "qqbot",
              "sslMode": "PREFERRED",
              "connectTimeoutMs": 5000,
              "passwordFile": "db-password.txt"
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val key = ByteArray(32)
        Arrays.fill(key, 0x3c.toByte())
        val store = DatabaseConfigurationStore(
            activeFile,
            candidateFile,
            JsonMapper.builder().findAndAddModules().build(),
            AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test-key", key)),
        )

        val profile = store.loadCandidateRequired().profile
        profile.use {
            assertThat(profile.type).isEqualTo(DatabaseType.MYSQL)
            assertThat(profile.sslMode).isEqualTo(DatabaseSslMode.PREFERRED)
            val password = requireNotNull(profile.copyPassword())
            try {
                assertThat(password).containsExactly(*"candidate-secret".toCharArray())
            } finally {
                Arrays.fill(password, '\u0000')
            }
        }
    }

    @Test
    fun `resolves SQLite path relative to candidate configuration`() {
        val activeDirectory = Files.createDirectory(temporaryDirectory.resolve("active-sqlite"))
        val candidateDirectory = Files.createDirectory(temporaryDirectory.resolve("candidate-sqlite"))
        val candidateFile = candidateDirectory.resolve("database.json.candidate")
        Files.writeString(
            candidateFile,
            """
            {
              "type": "SQLITE",
              "sqlitePath": "data/qqbot.db",
              "busyTimeoutMs": 5000
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val store = DatabaseConfigurationStore(
            activeDirectory.resolve("database.json"),
            candidateFile,
            JsonMapper.builder().findAndAddModules().build(),
            AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test-key", ByteArray(32))),
        )

        store.loadCandidateRequired().profile.use { profile ->
            assertThat(profile.type).isEqualTo(DatabaseType.SQLITE)
            assertThat(profile.sqlitePath)
                .isEqualTo(candidateDirectory.resolve("data/qqbot.db").toAbsolutePath().normalize())
        }
    }
}
