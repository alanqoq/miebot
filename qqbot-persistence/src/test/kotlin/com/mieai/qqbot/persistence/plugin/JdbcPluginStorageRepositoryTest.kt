package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot
import com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPluginStorageRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var dataSource: DataSource
    private lateinit var storage: JdbcPluginStorageRepository

    @BeforeEach
    fun setUp() {
        dataSource = migratedDatabase(temporaryDirectory.resolve("plugin-storage.db"))
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX)
        JdbcPluginArtifactRepository(dataSource).upsert(
            PluginArtifact(
                "echo", "Echo Reply", "1.0.0", "1.0.0", "echo.jar", "sha256", "factory",
                "LOADED", true, BASE_TIME, BASE_TIME,
            ),
        )
        JdbcBotPluginBindingRepository(dataSource).insert(binding(BINDING, BOT))
        storage = JdbcPluginStorageRepository(dataSource)
    }

    @Test
    fun persistsUpdatesListsAndDeletesValuesWithinOneBinding() {
        storage.put(BINDING, "settings", "color", "blue", BASE_TIME)
        assertThat(storage.find(BINDING, "settings", "color")).isEqualTo("blue")

        storage.put(BINDING, "settings", "color", "green", BASE_TIME.plusSeconds(1))
        storage.put(BINDING, "settings", "enabled", "true", BASE_TIME.plusSeconds(1))
        assertThat(storage.list(BINDING, "settings"))
            .containsEntry("color", "green")
            .containsEntry("enabled", "true")

        storage.delete(BINDING, "settings", "color")
        assertThat(storage.find(BINDING, "settings", "color")).isNull()
        assertThat(storage.list(BINDING, "settings")).containsOnlyKeys("enabled")
    }

    @Test
    fun isolatesValuesByBindingAndCascadesOnBindingDeletion() {
        val secondBinding = UUID.fromString("770e8400-e29b-41d4-a716-446655440002")
        val secondBot = "550e8400-e29b-41d4-a716-446655440002"
        insertBot(dataSource, secondBot, "10002", BotEnvironment.SANDBOX)
        JdbcBotPluginBindingRepository(dataSource).insert(binding(secondBinding, secondBot))

        storage.put(BINDING, "state", "value", "first", BASE_TIME)
        storage.put(secondBinding, "state", "value", "second", BASE_TIME)
        assertThat(storage.find(BINDING, "state", "value")).isEqualTo("first")
        assertThat(storage.find(secondBinding, "state", "value")).isEqualTo("second")

        JdbcBotPluginBindingRepository(dataSource).delete(BINDING)
        assertThat(storage.list(BINDING, "state")).isEmpty()
        assertThat(storage.find(secondBinding, "state", "value")).isEqualTo("second")
    }

    @Test
    fun rejectsUnsafeKeysAndOversizedValues() {
        assertThatThrownBy { storage.put(BINDING, "bad namespace", "key", "value", BASE_TIME) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { storage.put(BINDING, "state", "bad key", "value", BASE_TIME) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { storage.put(BINDING, "state", "key", "x".repeat(65_537), BASE_TIME) }
            .isInstanceOf(IllegalArgumentException::class.java)

        val put = JdbcPluginStorageRepository::class.java.getMethod(
            "put",
            UUID::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Instant::class.java,
        )
        assertThatThrownBy { put.invoke(storage, BINDING, "state", "key", null, Instant.now()) }
            .hasRootCauseInstanceOf(NullPointerException::class.java)
    }

    private fun binding(id: UUID, botId: String) = BotPluginBinding(
        id,
        "echo",
        BotId.parse(botId),
        true,
        0L,
        BASE_TIME,
        BASE_TIME,
        PluginBindingRuntimeState.ACTIVE,
        null,
    )

    companion object {
        private const val BOT = "550e8400-e29b-41d4-a716-446655440001"
        private val BINDING = UUID.fromString("770e8400-e29b-41d4-a716-446655440001")
    }
}
