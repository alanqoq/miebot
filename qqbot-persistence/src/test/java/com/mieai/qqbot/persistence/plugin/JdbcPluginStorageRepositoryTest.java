package com.mieai.qqbot.persistence.plugin;

import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcPluginStorageRepositoryTest {
    private static final String BOT = "550e8400-e29b-41d4-a716-446655440001";
    private static final UUID BINDING = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");

    @TempDir
    Path temporaryDirectory;

    private DataSource dataSource;
    private JdbcPluginStorageRepository storage;

    @BeforeEach
    void setUp() {
        dataSource = migratedDatabase(temporaryDirectory.resolve("plugin-storage.db"));
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX);
        new JdbcPluginArtifactRepository(dataSource).upsert(new PluginArtifact(
                "echo", "Echo Reply", "1.0.0", "1.0.0", "echo.jar", "sha256", "factory",
                "LOADED", true, BASE_TIME, BASE_TIME));
        new JdbcBotPluginBindingRepository(dataSource).insert(new BotPluginBinding(
                BINDING, "echo", BotId.parse(BOT), "{}", true, 0, BASE_TIME, BASE_TIME));
        storage = new JdbcPluginStorageRepository(dataSource);
    }

    @Test
    void persistsUpdatesListsAndDeletesValuesWithinOneBinding() {
        storage.put(BINDING, "settings", "color", "blue", BASE_TIME);
        assertThat(storage.find(BINDING, "settings", "color")).contains("blue");

        storage.put(BINDING, "settings", "color", "green", BASE_TIME.plusSeconds(1));
        storage.put(BINDING, "settings", "enabled", "true", BASE_TIME.plusSeconds(1));
        assertThat(storage.list(BINDING, "settings"))
                .containsEntry("color", "green")
                .containsEntry("enabled", "true");

        storage.delete(BINDING, "settings", "color");
        assertThat(storage.find(BINDING, "settings", "color")).isEmpty();
        assertThat(storage.list(BINDING, "settings")).containsOnlyKeys("enabled");
    }

    @Test
    void isolatesValuesByBindingAndCascadesOnBindingDeletion() {
        UUID secondBinding = UUID.fromString("770e8400-e29b-41d4-a716-446655440002");
        String secondBot = "550e8400-e29b-41d4-a716-446655440002";
        insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX);
        new JdbcBotPluginBindingRepository(dataSource).insert(new BotPluginBinding(
                secondBinding, "echo", BotId.parse(secondBot), "{}", true, 0, BASE_TIME, BASE_TIME));

        storage.put(BINDING, "state", "value", "first", BASE_TIME);
        storage.put(secondBinding, "state", "value", "second", BASE_TIME);
        assertThat(storage.find(BINDING, "state", "value")).contains("first");
        assertThat(storage.find(secondBinding, "state", "value")).contains("second");

        new JdbcBotPluginBindingRepository(dataSource).delete(BINDING);
        assertThat(storage.list(BINDING, "state")).isEmpty();
        assertThat(storage.find(secondBinding, "state", "value")).contains("second");
    }

    @Test
    void rejectsUnsafeKeysAndOversizedValues() {
        assertThatThrownBy(() -> storage.put(BINDING, "bad namespace", "key", "value", BASE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(BINDING, "state", "bad key", "value", BASE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(BINDING, "state", "key", "x".repeat(65_537), BASE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(BINDING, "state", "key", null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
