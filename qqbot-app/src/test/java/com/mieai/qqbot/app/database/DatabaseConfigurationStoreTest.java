package com.mieai.qqbot.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mieai.qqbot.admin.database.DatabaseSslMode;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.StaticKeyProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseConfigurationStoreTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void resolvesPasswordFileRelativeToCandidateConfiguration() throws Exception {
        Path activeDirectory = Files.createDirectory(temporaryDirectory.resolve("active"));
        Path candidateDirectory = Files.createDirectory(temporaryDirectory.resolve("candidate"));
        Path activeFile = activeDirectory.resolve("database.json");
        Path candidateFile = candidateDirectory.resolve("database.json.candidate");
        Files.writeString(
                candidateDirectory.resolve("db-password.txt"),
                "candidate-secret\n",
                StandardCharsets.UTF_8);
        Files.writeString(candidateFile, """
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
                """, StandardCharsets.UTF_8);

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x3c);
        DatabaseConfigurationStore store = new DatabaseConfigurationStore(
                activeFile,
                candidateFile,
                JsonMapper.builder().findAndAddModules().build(),
                new AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test-key", key)));

        DatabaseConfigurationStore.LoadedConfiguration loaded = store.loadCandidateRequired();
        try (DatabaseProfile profile = loaded.profile()) {
            assertThat(profile.type()).isEqualTo(DatabaseType.MYSQL);
            assertThat(profile.sslMode()).isEqualTo(DatabaseSslMode.PREFERRED);
            char[] password = profile.copyPassword();
            try {
                assertThat(password).containsExactly("candidate-secret".toCharArray());
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    @Test
    void resolvesSQLitePathRelativeToCandidateConfiguration() throws Exception {
        Path activeDirectory = Files.createDirectory(temporaryDirectory.resolve("active-sqlite"));
        Path candidateDirectory = Files.createDirectory(temporaryDirectory.resolve("candidate-sqlite"));
        Path candidateFile = candidateDirectory.resolve("database.json.candidate");
        Files.writeString(candidateFile, """
                {
                  "type": "SQLITE",
                  "sqlitePath": "data/qqbot.db",
                  "busyTimeoutMs": 5000
                }
                """, StandardCharsets.UTF_8);

        byte[] key = new byte[32];
        DatabaseConfigurationStore store = new DatabaseConfigurationStore(
                activeDirectory.resolve("database.json"),
                candidateFile,
                JsonMapper.builder().findAndAddModules().build(),
                new AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test-key", key)));

        DatabaseConfigurationStore.LoadedConfiguration loaded = store.loadCandidateRequired();
        try (DatabaseProfile profile = loaded.profile()) {
            assertThat(profile.type()).isEqualTo(DatabaseType.SQLITE);
            assertThat(profile.sqlitePath())
                    .isEqualTo(candidateDirectory.resolve("data/qqbot.db").toAbsolutePath().normalize());
        }
    }
}
