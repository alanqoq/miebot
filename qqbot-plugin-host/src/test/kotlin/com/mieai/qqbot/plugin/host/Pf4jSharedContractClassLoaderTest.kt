package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.JdbcBotRepository
import com.mieai.qqbot.persistence.inbox.IncomingEvent
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository
import com.mieai.qqbot.persistence.outbox.OutboxQuery
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer
import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import com.mieai.qqbot.plugin.spi.PluginApiVersion
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HashSet
import java.util.UUID
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.sql.DataSource

class Pf4jSharedContractClassLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun externalPluginWithBundledSharedContractsCreatesTextOutboxJob() {
        val pluginDirectory = temporaryDirectory.resolve("plugins")
        val pluginDataRoot = temporaryDirectory.resolve("plugin-data")
        Files.createDirectories(pluginDirectory)
        writePluginJar(pluginDirectory.resolve("shared-contract-shadow.jar"))

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        insertBot(dataSource)
        val outbox = JdbcOutboxRepository(dataSource)
        val inbox = JdbcEventInboxRepository(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)

        Pf4jPluginHost(
            pluginDirectory,
            pluginDataRoot,
            JdbcPluginArtifactRepository(dataSource),
            JdbcBotRepository(dataSource),
            outbox,
            ObjectMapper(),
            clock,
        ).use { host ->
            host.start()
            assertThat(host.isLoaded(PLUGIN_ID)).isTrue()

            val binding = BotPluginBinding(
                UUID.randomUUID(),
                PLUGIN_ID,
                BotId.parse(BOT_ID),
                true,
                0,
                NOW,
                NOW,
                PluginBindingRuntimeState.ACTIVE,
                null,
            )
            JdbcBotPluginBindingRepository(dataSource).insert(binding)
            Files.createDirectories(host.bindingDataDirectory(binding))
            Files.writeString(host.configurationFile(binding), "{}")
            val event = inbox.insertOrGet(
                IncomingEvent(
                    UUID.randomUUID(),
                    BotEnvironment.SANDBOX,
                    binding.botId,
                    "C2C_MESSAGE_CREATE",
                    "shared-contract-event",
                    "{\"id\":\"shared-contract-event\",\"d\":{}}",
                    NOW,
                ),
            ).event

            host.execute(binding, event).toCompletableFuture().join()

            val job = outbox.query(OutboxQuery.firstPage(10)).jobs.single()
            val stored = checkNotNull(outbox.findById(job.id))
            assertThat(stored.jobType).isEqualTo(OutboundTextPayload.JOB_TYPE)
            assertThat(stored.sourceEventId).isEqualTo(event.id)
            assertThat(stored.payload).contains("shared contract reply").contains("user-1")
        }
    }

    private fun writePluginJar(target: Path) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Plugin-Id", PLUGIN_ID)
            mainAttributes.putValue("Plugin-Name", PLUGIN_ID)
            mainAttributes.putValue("Plugin-Version", "1.0.0")
            mainAttributes.putValue("Plugin-Requires", PluginApiVersion.CURRENT)
            mainAttributes.putValue("Plugin-Class", Pf4jPluginBridge::class.java.name)
            mainAttributes.putValue("Plugin-Config-Schema", "plugin-schema.json")
            mainAttributes.putValue("Plugin-Default-Config", "config.json")
            mainAttributes.putValue("Plugin-Capabilities", "event.subscribe,message.send")
        }
        val entries = HashSet<String>()
        JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
            addClassFamily(output, entries, BundledSharedContractPluginFactory::class.java)
            addPackageClasses(output, entries, TextMessage::class.java, "com/mieai/qqbot/plugin/api/")
            addPackageClasses(output, entries, BotPluginFactory::class.java, "com/mieai/qqbot/plugin/spi/")
            addPackageClasses(output, entries, BotId::class.java, "com/mieai/qqbot/domain/")
            addTextEntry(
                output,
                entries,
                "META-INF/services/${BotPluginFactory::class.java.name}",
                BundledSharedContractPluginFactory::class.java.name,
            )
            addTextEntry(output, entries, "config.json", "{}")
            addTextEntry(output, entries, "plugin-schema.json", "{\"type\":\"object\",\"additionalProperties\":false}")
        }
        JarFile(target.toFile(), false).use { jar ->
            listOf(
                BundledSharedContractPluginFactory::class.java,
                TextMessage::class.java,
                BotPluginFactory::class.java,
                BotId::class.java,
            ).forEach { type ->
                val entry = type.name.replace('.', '/') + ".class"
                check(jar.getJarEntry(entry) != null) { "shadow plugin is missing $entry" }
            }
        }
    }

    private fun addClassFamily(output: JarOutputStream, entries: MutableSet<String>, anchor: Class<*>) {
        val packagePath = anchor.packageName.replace('.', '/') + "/"
        addClasses(output, entries, anchor) { name ->
            name.startsWith(packagePath + "BundledSharedContractPlugin") && name.endsWith(".class")
        }
    }

    private fun addPackageClasses(
        output: JarOutputStream,
        entries: MutableSet<String>,
        anchor: Class<*>,
        packagePath: String,
    ) {
        addClasses(output, entries, anchor) { name -> name.startsWith(packagePath) && name.endsWith(".class") }
    }

    private fun addClasses(
        output: JarOutputStream,
        entries: MutableSet<String>,
        anchor: Class<*>,
        include: (String) -> Boolean,
    ) {
        val codeSource = requireNotNull(anchor.protectionDomain.codeSource) { "class has no code source: ${anchor.name}" }
        val location = Path.of(codeSource.location.toURI())
        if (Files.isDirectory(location)) {
            Files.walk(location).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    val name = location.relativize(file).toString().replace(File.separatorChar, '/')
                    if (include(name)) addFileEntry(output, entries, name, file)
                }
            }
            return
        }
        JarFile(location.toFile(), false).use { jar ->
            val candidates = jar.entries().asSequence()
                .filter { entry -> !entry.isDirectory && include(entry.name) }
                .sortedBy(JarEntry::getName)
            for (entry in candidates) {
                if (!entries.add(entry.name)) continue
                output.putNextEntry(JarEntry(entry.name))
                jar.getInputStream(entry).use { input -> input.transferTo(output) }
                output.closeEntry()
            }
        }
    }

    private fun addFileEntry(
        output: JarOutputStream,
        entries: MutableSet<String>,
        name: String,
        file: Path,
    ) {
        if (!entries.add(name)) return
        output.putNextEntry(JarEntry(name))
        Files.newInputStream(file).use { input -> input.transferTo(output) }
        output.closeEntry()
    }

    private fun addTextEntry(
        output: JarOutputStream,
        entries: MutableSet<String>,
        name: String,
        value: String,
    ) {
        check(entries.add(name)) { "duplicate JAR entry: $name" }
        output.putNextEntry(JarEntry(name))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.closeEntry()
    }

    private fun insertBot(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bots (id, display_name, app_id, environment, app_secret_ciphertext,
                    app_secret_key_id, intents, shard_index, shard_count, enabled, revision, created_at, updated_at)
                VALUES (?, 'Shared Contract Bot', '10001', 'SANDBOX', 'ciphertext', 'primary',
                    33554432, 0, 1, 1, 1, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, BOT_ID)
                statement.setString(2, NOW.toString())
                statement.setString(3, NOW.toString())
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val PLUGIN_ID = "shared-contract-shadow"
        const val BOT_ID = "550e8400-e29b-41d4-a716-446655440001"
        val NOW: Instant = Instant.parse("2026-08-03T00:00:00Z")
    }
}

class BundledSharedContractPluginFactory : BotPluginFactory {
    override val pluginId: String = "shared-contract-shadow"

    override fun create(context: PluginRuntimeContext): BotPlugin = BundledSharedContractPlugin(context)
}

private class BundledSharedContractPlugin(
    private val context: PluginRuntimeContext,
) : BotPlugin {
    private var subscription: EventSubscription? = null

    override fun start() {
        subscription = context.events.subscribe("direct-text-message", setOf("C2C_MESSAGE_CREATE")) { event ->
            context.base.messageSender.enqueue(
                TextMessage(
                    MessageTarget(MessageTargetType.C2C, "user-1"),
                    "shared contract reply",
                    sourceEventId = event.id,
                ),
            ).thenApply<Void> { null }
        }
    }

    override fun stop() {
        subscription?.close()
        subscription = null
    }
}
