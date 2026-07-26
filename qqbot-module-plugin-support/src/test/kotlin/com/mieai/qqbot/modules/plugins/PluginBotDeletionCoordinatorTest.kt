package com.mieai.qqbot.modules.plugins

import com.mieai.qqbot.admin.plugins.PluginAdministrationException
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener
import com.mieai.qqbot.runtime.configuration.BotConfigurationService
import com.mieai.qqbot.runtime.security.AppSecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PluginBotDeletionCoordinatorTest {
    @TempDir
    lateinit var dataRoot: Path

    private val botId = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
    private lateinit var bots: BotRepository
    private lateinit var host: Pf4jPluginHost
    private lateinit var coordinator: PluginBotDeletionCoordinator

    @BeforeEach
    fun setUp() {
        bots = mock(BotRepository::class.java)
        host = mock(Pf4jPluginHost::class.java)
        `when`(host.pluginDataRoot).thenReturn(dataRoot)
        coordinator = PluginBotDeletionCoordinator(bots, host)
    }

    @Test
    fun `restores the atomically isolated directory when database deletion fails`() {
        botExists()
        val config = createBotData().resolve("config.json")
        Files.writeString(config, "{}")

        coordinator.prepare(botId)

        assertThat(botRoot()).doesNotExist()
        assertThat(tombstones()).hasSize(1)

        assertThat(coordinator.abort(botId)).isEqualTo(PluginBotDeletionCoordinator.AbortResolution.RESTORED)
        assertThat(config).hasContent("{}")
        assertThat(tombstones()).isEmpty()
    }

    @Test
    fun `tombstone preparation failure prevents the durable delete`() {
        botExists()
        Files.writeString(botRoot(), "not a directory")
        val listener = object : BotConfigurationChangeListener {
            override fun beforeDelete(botId: BotId) = coordinator.prepare(botId)

            override fun onDeleteAborted(botId: BotId) {
                coordinator.abort(botId)
            }

            override fun onCommitted(change: BotConfigurationChange) = Unit
        }
        val service = BotConfigurationService(
            bots,
            mock(AppSecretCipher::class.java),
            changeListener = listener,
        )

        assertThatThrownBy { service.delete(botId) }
            .isInstanceOfSatisfying(PluginAdministrationException::class.java) { failure ->
                assertThat(failure.code).isEqualTo("PLUGIN_DATA_DIRECTORY_INVALID")
            }

        verify(bots, never()).delete(botId)
        assertThat(botRoot()).isRegularFile()
        assertThat(tombstones()).isEmpty()
    }

    @Test
    fun `does not restore data when a failed database call actually committed`() {
        `when`(bots.findById(botId)).thenReturn(presentBot()).thenReturn(null)
        createBotData()

        coordinator.prepare(botId)

        assertThat(coordinator.abort(botId))
            .isEqualTo(PluginBotDeletionCoordinator.AbortResolution.DELETION_COMMITTED)
        assertThat(botRoot()).doesNotExist()
        assertThat(tombstones()).isEmpty()
    }

    @Test
    fun `database exception after an actual commit cleans data and does not re-enable runtime`() {
        val exists = AtomicBoolean(true)
        val stored = mock(StoredBot::class.java)
        `when`(bots.findById(botId)).thenAnswer {
            if (exists.get()) stored else null
        }
        `when`(bots.delete(botId)).thenAnswer {
            exists.set(false)
            throw IllegalStateException("database connection failed after commit")
        }
        createBotData()
        val runtime = mock(PluginRuntimeService::class.java)
        val listener = PluginSupportModuleConfiguration().pluginBotDeletionListener(runtime, coordinator)
        val service = BotConfigurationService(
            bots,
            mock(AppSecretCipher::class.java),
            changeListener = listener,
        )

        assertThatThrownBy { service.delete(botId) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("database connection failed after commit")

        verify(runtime).botDeletionCompleted(botId)
        verify(runtime, never()).botDeletionAborted(botId)
        assertThat(botRoot()).doesNotExist()
        assertThat(tombstones()).isEmpty()
    }

    @Test
    fun `removes the tombstone only after the durable bot row is gone`() {
        `when`(bots.findById(botId)).thenReturn(presentBot()).thenReturn(null)
        createBotData()

        coordinator.prepare(botId)
        coordinator.commit(botId)

        assertThat(botRoot()).doesNotExist()
        assertThat(tombstones()).isEmpty()
    }

    @Test
    fun `failed committed cleanup is reported and remains recoverable`() {
        `when`(bots.findById(botId)).thenReturn(presentBot()).thenReturn(null)
        coordinator = PluginBotDeletionCoordinator(bots, host) {
            throw IOException("file is still open")
        }
        createBotData()

        coordinator.prepare(botId)

        assertThatThrownBy { coordinator.commit(botId) }
            .isInstanceOfSatisfying(PluginAdministrationException::class.java) { failure ->
                assertThat(failure.code).isEqualTo("BOT_PLUGIN_DATA_DELETE_FAILED")
            }
        assertThat(botRoot()).doesNotExist()
        assertThat(tombstones()).hasSize(1)

        PluginBotDeletionCoordinator(bots, host).recoverPendingDeletions()

        assertThat(tombstones()).isEmpty()
    }

    @Test
    fun `startup recovery restores a pre-commit tombstone`() {
        botExists()
        val tombstone = createTombstone()
        Files.writeString(tombstone.resolve("state.sqlite"), "database")

        coordinator.recoverPendingDeletions()

        assertThat(botRoot().resolve("state.sqlite")).hasContent("database")
        assertThat(tombstone).doesNotExist()
    }

    @Test
    fun `startup recovery retries cleanup for a committed deletion`() {
        `when`(bots.findById(botId)).thenReturn(null)
        val tombstone = createTombstone()
        Files.writeString(tombstone.resolve("large.bin"), "content")

        coordinator.start()

        assertThat(tombstone).doesNotExist()
        assertThat(coordinator.isRunning).isTrue()
        assertThat(coordinator.phase).isLessThan(Int.MAX_VALUE - 1_200)
    }

    private fun botExists() {
        `when`(bots.findById(botId)).thenReturn(presentBot())
    }

    private fun presentBot(): StoredBot = mock(StoredBot::class.java)

    private fun createBotData(): Path = Files.createDirectories(botRoot().resolve("echo-plugin"))

    private fun createTombstone(): Path = Files.createDirectories(
        dataRoot.resolve(".bot-delete-pending-${botId}-${UUID.randomUUID()}"),
    )

    private fun botRoot(): Path = dataRoot.resolve(botId.toString())

    private fun tombstones(): List<Path> = Files.list(dataRoot).use { paths ->
        paths.filter { it.fileName.toString().startsWith(".bot-delete-pending-${botId}-") }.toList()
    }
}
