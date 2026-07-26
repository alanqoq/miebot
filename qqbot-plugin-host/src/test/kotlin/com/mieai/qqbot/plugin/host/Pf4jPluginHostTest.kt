package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.JdbcBotRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.inbox.IncomingEvent
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository
import com.mieai.qqbot.persistence.outbox.OutboxQuery
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginStorageRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.sql.DataSource

class Pf4jPluginHostTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun loadsExamplePluginAndUsesEachBindingsKeywordReplyConfiguration() {
        val pluginDirectory = temporaryDirectory.resolve("plugins")
        val pluginDataRoot = temporaryDirectory.resolve("plugin-data")
        Files.createDirectories(pluginDirectory)
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        Files.copy(example, pluginDirectory.resolve(example.fileName))

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        insertBot(dataSource)
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        val outbox = JdbcOutboxRepository(dataSource)
        val inbox = JdbcEventInboxRepository(dataSource)
        val bindingRepository = JdbcBotPluginBindingRepository(dataSource)
        val storage = JdbcPluginStorageRepository(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)

        Pf4jPluginHost(
            pluginDirectory,
            pluginDataRoot,
            artifacts,
            JdbcBotRepository(dataSource),
            outbox,
            ObjectMapper(),
            clock,
            storage,
        ).use { host ->
            host.start()
            assertThat(host.isLoaded("example")).isTrue()
            val metadata = host.loadedPlugins().single()
            assertThat(metadata.capabilities).contains("http")
            assertThat(metadata.defaultConfigurationPath).isEqualTo("config.json")
            assertThat(metadata.defaultConfiguration).isEqualTo(DEFAULT_CONFIGURATION)
            assertThat(host.defaultConfiguration("example")).isEqualTo(DEFAULT_CONFIGURATION)
            assertThat(artifacts.findById("example")).isNotNull()
            val missingDefault = pluginWithoutManifestAttribute(
                example,
                temporaryDirectory.resolve("invalid-plugin/example.jar"),
                "Plugin-Default-Config",
            )
            assertThatThrownBy { host.validateArtifact(missingDefault) }
                .hasMessageContaining("must declare Plugin-Default-Config")

            val binding = BotPluginBinding(
                UUID.randomUUID(),
                "example",
                BotId.parse(BOT),
                true,
                0,
                NOW,
                NOW,
                PluginBindingRuntimeState.ACTIVE,
                null,
            )
            writeConfiguration(host, binding, DEFAULT_CONFIGURATION)
            assertThat(host.bindingDataDirectory(binding))
                .isEqualTo(pluginDataRoot.resolve(BOT).resolve("example").toAbsolutePath().normalize())
            bindingRepository.insert(binding)
            assertThat(host.handlerIds(binding, "C2C_MESSAGE_CREATE")).containsExactly("keyword-reply")
            val incoming = IncomingEvent(
                UUID.randomUUID(),
                BotEnvironment.SANDBOX,
                BotId.parse(BOT),
                "C2C_MESSAGE_CREATE",
                "message-1",
                "{\"id\":\"event-1\",\"d\":{\"id\":\"message-1\",\"content\":\"/example\",\"author\":{\"user_openid\":\"user-1\"}}}",
                NOW,
            )
            val event = inbox.insertOrGet(incoming).event

            host.execute(binding, event).toCompletableFuture().join()

            val jobs = outbox.query(OutboxQuery.firstPage(10)).jobs
            val job = jobs.single()
            val stored = checkNotNull(outbox.findById(job.id))
            assertThat(stored.jobType).isEqualTo(OutboundTextPayload.JOB_TYPE)
            assertThat(stored.sourceEventId).isEqualTo(event.id)
            assertThat(stored.payload).contains("example reply").contains("user-1")

            val secondBinding = BotPluginBinding(
                UUID.randomUUID(),
                "example",
                BotId.parse("550e8400-e29b-41d4-a716-446655440002"),
                true,
                0,
                NOW,
                NOW,
                PluginBindingRuntimeState.ACTIVE,
                null,
            )
            writeConfiguration(host, secondBinding, SECOND_CONFIGURATION)
            insertBot(dataSource, secondBinding.botId.toString(), "10002")
            bindingRepository.insert(secondBinding)
            val notMatched = inbox.insertOrGet(
                IncomingEvent(
                    UUID.randomUUID(),
                    BotEnvironment.SANDBOX,
                    secondBinding.botId,
                    "C2C_MESSAGE_CREATE",
                    "not-matched",
                    "{\"id\":\"not-matched\",\"d\":{\"id\":\"message-2\",\"content\":\"/example\",\"author\":{\"user_openid\":\"user-2\"}}}",
                    NOW,
                ),
            ).event
            val matched = inbox.insertOrGet(
                IncomingEvent(
                    UUID.randomUUID(),
                    BotEnvironment.SANDBOX,
                    secondBinding.botId,
                    "C2C_MESSAGE_CREATE",
                    "matched",
                    "{\"id\":\"matched\",\"d\":{\"id\":\"message-3\",\"content\":\"hello\",\"author\":{\"user_openid\":\"user-2\"}}}",
                    NOW,
                ),
            ).event
            host.execute(secondBinding, notMatched).toCompletableFuture().join()
            assertThat(outbox.query(OutboxQuery.firstPage(10)).jobs).hasSize(1)
            host.execute(secondBinding, matched).toCompletableFuture().join()
            val payloads = outbox.query(OutboxQuery.firstPage(10)).jobs
                .mapNotNull { outbox.findById(it.id)?.payload }
            assertThat(payloads).hasSize(2)
            assertThat(payloads).anySatisfy { payload ->
                assertThat(payload).contains("configured response").contains("user-2")
            }

            host.invalidateBot(binding.botId)
            assertThat(activeBindingIds(host)).containsExactly(secondBinding.id)

            val invalidBinding = BotPluginBinding(
                UUID.randomUUID(),
                "example",
                BotId.parse("550e8400-e29b-41d4-a716-446655440003"),
                true,
                0,
                NOW,
                NOW,
                PluginBindingRuntimeState.ACTIVE,
                null,
            )
            assertThatThrownBy { host.handlerIds(invalidBinding) }
                .hasMessageContaining("data directory does not exist")
            writeConfiguration(host, invalidBinding, "[]")
            assertThatThrownBy { host.handlerIds(invalidBinding) }
                .hasMessageContaining("must be a JSON object")
            writeConfiguration(host, invalidBinding, "{\"unexpected\":true}")
            assertThatThrownBy { host.handlerIds(invalidBinding) }
                .hasMessageContaining("failed schema validation")
                .hasMessageContaining("$.unexpected is not allowed")
        }
    }

    @Test
    fun reconcilesDeletedDisabledPausedChangedAndRevisedInstancesWithoutCreatingNewOnes() {
        val pluginDirectory = temporaryDirectory.resolve("reconcile-plugins")
        val pluginDataRoot = temporaryDirectory.resolve("reconcile-plugin-data")
        Files.createDirectories(pluginDirectory)
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        Files.copy(example, pluginDirectory.resolve(example.fileName))

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("reconcile-host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        insertBot(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)

        Pf4jPluginHost(
            pluginDirectory,
            pluginDataRoot,
            JdbcPluginArtifactRepository(dataSource),
            JdbcBotRepository(dataSource),
            JdbcOutboxRepository(dataSource),
            ObjectMapper(),
            clock,
            JdbcPluginStorageRepository(dataSource),
        ).use { host ->
            host.start()
            val retained = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val deleted = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val disabled = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val paused = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val renamed = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val revised = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val notMaterialized = binding(UUID.randomUUID(), "example", true, 1, PluginBindingRuntimeState.ACTIVE)
            val materialized = listOf(retained, deleted, disabled, paused, renamed, revised)
            writeConfiguration(host, retained, DEFAULT_CONFIGURATION)
            materialized.forEach { host.handlerIds(it) }
            assertThat(activeBindingIds(host)).containsExactlyInAnyOrderElementsOf(materialized.map { it.id })

            host.reconcileBindings(
                listOf(
                    retained,
                    binding(disabled.id, "example", false, 1, PluginBindingRuntimeState.ACTIVE),
                    binding(paused.id, "example", true, 1, PluginBindingRuntimeState.PAUSED),
                    binding(renamed.id, "renamed", true, 1, PluginBindingRuntimeState.ACTIVE),
                    binding(revised.id, "example", true, 2, PluginBindingRuntimeState.ACTIVE),
                    notMaterialized,
                ),
            )

            assertThat(activeBindingIds(host)).containsExactly(retained.id)
        }
    }

    @Test
    fun rejectsDefaultConfigurationResourcesLargerThan64KiBDuringValidationAndLoading() {
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        val oversizedJson = ("{\n" + " ".repeat(64 * 1024) + "\n}").toByteArray(StandardCharsets.UTF_8)
        val oversizedArtifact = pluginWithResource(
            example,
            temporaryDirectory.resolve("oversized-staging/example.jar"),
            "config.json",
            oversizedJson,
        )
        val pluginDirectory = temporaryDirectory.resolve("oversized-plugins")
        Files.createDirectories(pluginDirectory)

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("oversized-host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        Pf4jPluginHost(
            pluginDirectory,
            temporaryDirectory.resolve("oversized-plugin-data"),
            artifacts,
            JdbcBotRepository(dataSource),
            JdbcOutboxRepository(dataSource),
            ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            JdbcPluginStorageRepository(dataSource),
        ).use { host ->
            assertThatThrownBy { host.validateArtifact(oversizedArtifact) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("cannot exceed 64 KiB")

            Files.copy(oversizedArtifact, pluginDirectory.resolve("example.jar"))
            host.start()

            assertThat(host.isLoaded("example")).isFalse()
            assertThat(artifacts.findById("example")).isNull()
        }
    }

    @Test
    fun rejectsNonCanonicalPluginIdsBeforeStartingTheArtifact() {
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        val invalidIds = listOf("Example", "a/../example", "example/", "example.", "con", "con.settings")

        validationHost("invalid-id").use { host ->
            invalidIds.forEachIndexed { index, pluginId ->
                val candidate = pluginWithManifestValue(
                    example,
                    temporaryDirectory.resolve("invalid-id-$index/example.jar"),
                    "Plugin-Id",
                    pluginId,
                )

                assertThatThrownBy { host.validateArtifact(candidate) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Plugin id")
            }
        }
    }

    @Test
    fun requiresSchemaAndDefaultConfigurationToBelongToTheCurrentJar() {
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        val missingSchema = pluginWithManifestValue(
            example,
            temporaryDirectory.resolve("parent-schema/example.jar"),
            "Plugin-Config-Schema",
            "parent-only-plugin-resource.json",
        )
        val missingDefault = pluginWithManifestValue(
            example,
            temporaryDirectory.resolve("parent-default/example.jar"),
            "Plugin-Default-Config",
            "parent-only-plugin-resource.json",
        )

        assertThat(Pf4jPluginHostTest::class.java.classLoader.getResource("parent-only-plugin-resource.json"))
            .isNotNull()
        validationHost("own-resources").use { host ->
            assertThatThrownBy { host.validateArtifact(missingSchema) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("schema resource is missing")
            assertThatThrownBy { host.validateArtifact(missingDefault) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("default configuration resource is missing")
        }
    }

    @Test
    fun enforcesRuntimeConfigurationUtf8AndExact64KiBBoundary() {
        val pluginDirectory = temporaryDirectory.resolve("configuration-plugins")
        val pluginDataRoot = temporaryDirectory.resolve("configuration-plugin-data")
        Files.createDirectories(pluginDirectory)
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        Files.copy(example, pluginDirectory.resolve(example.fileName))

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("configuration-host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        insertBot(dataSource)
        Pf4jPluginHost(
            pluginDirectory,
            pluginDataRoot,
            JdbcPluginArtifactRepository(dataSource),
            JdbcBotRepository(dataSource),
            JdbcOutboxRepository(dataSource),
            ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            JdbcPluginStorageRepository(dataSource),
        ).use { host ->
            host.start()
            val binding = binding(UUID.randomUUID(), "example", true, 0, PluginBindingRuntimeState.ACTIVE)
            Files.createDirectories(host.bindingDataDirectory(binding))

            val atLimit = ByteArray(64 * 1024) { ' '.code.toByte() }
            DEFAULT_CONFIGURATION.dropLast(1).toByteArray(StandardCharsets.UTF_8).copyInto(atLimit)
            atLimit[atLimit.lastIndex] = '}'.code.toByte()
            Files.write(host.configurationFile(binding), atLimit)
            assertThat(host.handlerIds(binding)).containsExactly("keyword-reply")
            host.invalidate(binding.id)

            Files.write(host.configurationFile(binding), atLimit.copyOf(atLimit.size + 1))
            assertThatThrownBy { host.handlerIds(binding) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("cannot exceed 64 KiB")

            Files.write(
                host.configurationFile(binding),
                byteArrayOf(
                    '{'.code.toByte(),
                    '"'.code.toByte(),
                    'x'.code.toByte(),
                    '"'.code.toByte(),
                    ':'.code.toByte(),
                    '"'.code.toByte(),
                    0xc3.toByte(),
                    0x28.toByte(),
                    '"'.code.toByte(),
                    '}'.code.toByte(),
                ),
            )
            assertThatThrownBy { host.handlerIds(binding) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("must be valid UTF-8")
        }
    }

    @Test
    fun strictBindingQuiescenceKeepsTimedOutHandleFencedUntilRetry() {
        val pluginDirectory = temporaryDirectory.resolve("strict-stop-plugins")
        val pluginDataRoot = temporaryDirectory.resolve("strict-stop-plugin-data")
        Files.createDirectories(pluginDirectory)
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        Files.copy(example, pluginDirectory.resolve(example.fileName))

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("strict-stop-host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        insertBot(dataSource)
        val inbox = JdbcEventInboxRepository(dataSource)
        Pf4jPluginHost(
            pluginDirectory,
            pluginDataRoot,
            JdbcPluginArtifactRepository(dataSource),
            JdbcBotRepository(dataSource),
            JdbcOutboxRepository(dataSource),
            ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            storage = JdbcPluginStorageRepository(dataSource),
            queueCapacity = 256,
            shutdownTimeout = Duration.ofMillis(25),
        ).use { host ->
            host.start()
            val binding = binding(UUID.randomUUID(), "example", true, 0, PluginBindingRuntimeState.ACTIVE)
            writeConfiguration(host, binding, DEFAULT_CONFIGURATION)
            host.handlerIds(binding)

            val pending = CompletableFuture<Void>()
            val entered = CountDownLatch(1)
            activeResources(host, binding.id).eventService().subscribe("blocking", setOf("C2C_MESSAGE_CREATE")) {
                entered.countDown()
                pending
            }
            val event = inbox.insertOrGet(
                IncomingEvent(
                    UUID.randomUUID(),
                    BotEnvironment.SANDBOX,
                    binding.botId,
                    "C2C_MESSAGE_CREATE",
                    "strict-stop-event",
                    "{\"id\":\"strict-stop-event\",\"d\":{}}",
                    NOW,
                ),
            ).event
            val execution = host.execute(binding, event, "blocking")
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy { host.quiesceBindingStrict(binding.id) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("still running")
            assertThat(activeBindingIds(host)).containsExactly(binding.id)

            pending.complete(null)
            execution.toCompletableFuture().join()
            host.quiesceBindingStrict(binding.id)
            assertThat(activeBindingIds(host)).isEmpty()
        }
    }

    @Test
    fun upgradesPluginWithoutRestartAndRestoresPreviousArtifactWhenInstallationFails() {
        val pluginDirectory = temporaryDirectory.resolve("hot-plugins")
        val pluginDataRoot = temporaryDirectory.resolve("hot-plugin-data")
        val stagingDirectory = pluginDirectory.resolve(".staging")
        Files.createDirectories(stagingDirectory)
        val example = Path.of(System.getProperty("qqbot.example.plugin"))
        val oldArtifact = pluginWithVersion(example, pluginDirectory.resolve("example-old.jar"), "1.0.0")

        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("hot-host.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        insertBot(dataSource)
        val artifacts = JdbcPluginArtifactRepository(dataSource)
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)

        Pf4jPluginHost(
            pluginDirectory,
            pluginDataRoot,
            artifacts,
            JdbcBotRepository(dataSource),
            JdbcOutboxRepository(dataSource),
            ObjectMapper(),
            clock,
            JdbcPluginStorageRepository(dataSource),
        ).use { host ->
            host.start()
            assertThat(host.loadedPlugins().single().version).isEqualTo("1.0.0")

            val binding = binding(UUID.randomUUID(), "example", true, 0, PluginBindingRuntimeState.ACTIVE)
            writeConfiguration(host, binding, DEFAULT_CONFIGURATION)
            assertThat(host.handlerIds(binding, "C2C_MESSAGE_CREATE")).containsExactly("keyword-reply")

            val upgrade = pluginWithVersion(example, stagingDirectory.resolve("example-upgrade.jar"), "2.0.0")
            val result = host.installArtifact(upgrade)

            assertThat(result.operation).isEqualTo("UPGRADED")
            assertThat(result.previousVersion).contains("1.0.0")
            assertThat(result.artifact.version).isEqualTo("2.0.0")
            assertThat(result.artifact.path).isRegularFile()
            assertThat(oldArtifact).doesNotExist()
            assertThat(upgrade).doesNotExist()
            assertThat(host.handlerIds(binding, "C2C_MESSAGE_CREATE")).containsExactly("keyword-reply")

            val identical = stagingDirectory.resolve("example-identical.jar")
            Files.copy(result.artifact.path, identical)
            val unchanged = host.installArtifact(identical)
            assertThat(unchanged.operation).isEqualTo("UNCHANGED")
            assertThat(identical).doesNotExist()

            val rejected = pluginWithVersion(example, stagingDirectory.resolve("example-rejected.jar"), "3.0.0")
            val candidate = host.validateArtifact(rejected)
            val occupiedTarget = pluginDirectory.resolve("example-3.0.0-${candidate.sha256.substring(0, 12)}.jar")
            Files.createDirectory(occupiedTarget)

            assertThatThrownBy { host.installArtifact(rejected) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("target already exists")
            assertThat(occupiedTarget).isDirectory()
            assertThat(host.loadedPlugins().single().version).isEqualTo("2.0.0")
        }
    }

    private fun insertBot(dataSource: DataSource) {
        insertBot(dataSource, BOT, "10001")
    }

    private fun insertBot(dataSource: DataSource, botId: String, appId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bots (id, display_name, app_id, environment, app_secret_ciphertext,
                    app_secret_key_id, intents, shard_index, shard_count, enabled, revision, created_at, updated_at)
                VALUES (?, 'Echo Bot', ?, 'SANDBOX', 'ciphertext', 'primary', 33554432, 0, 1, 1, 1, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, botId)
                statement.setString(2, appId)
                statement.setString(3, NOW.toString())
                statement.setString(4, NOW.toString())
                statement.executeUpdate()
            }
        }
    }

    private fun pluginWithVersion(source: Path, target: Path, version: String): Path {
        JarFile(source.toFile(), false).use { input ->
            val manifest = Manifest(input.manifest)
            manifest.mainAttributes.putValue("Plugin-Version", version)
            JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (JarFile.MANIFEST_NAME.equals(entry.name, ignoreCase = true)) continue
                    val copy = JarEntry(entry.name)
                    copy.time = entry.time
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.transferTo(output) }
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private fun pluginWithManifestValue(source: Path, target: Path, attribute: String, value: String): Path {
        Files.createDirectories(target.parent)
        JarFile(source.toFile(), false).use { input ->
            val manifest = Manifest(input.manifest)
            manifest.mainAttributes.putValue(attribute, value)
            JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (JarFile.MANIFEST_NAME.equals(entry.name, ignoreCase = true)) continue
                    val copy = JarEntry(entry.name)
                    copy.time = entry.time
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.transferTo(output) }
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private fun pluginWithoutManifestAttribute(source: Path, target: Path, attribute: String): Path {
        Files.createDirectories(target.parent)
        JarFile(source.toFile(), false).use { input ->
            val manifest = Manifest(input.manifest)
            manifest.mainAttributes.remove(Attributes.Name(attribute))
            JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (JarFile.MANIFEST_NAME.equals(entry.name, ignoreCase = true)) continue
                    output.putNextEntry(JarEntry(entry))
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.transferTo(output) }
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private fun pluginWithResource(source: Path, target: Path, resourceName: String, replacement: ByteArray): Path {
        Files.createDirectories(target.parent)
        var replaced = false
        JarFile(source.toFile(), false).use { input ->
            val manifest = Manifest(input.manifest)
            JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (JarFile.MANIFEST_NAME.equals(entry.name, ignoreCase = true)) continue
                    val copy = JarEntry(entry.name)
                    copy.time = entry.time
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) {
                        if (resourceName == entry.name) {
                            output.write(replacement)
                            replaced = true
                        } else {
                            input.getInputStream(entry).use { it.transferTo(output) }
                        }
                    }
                    output.closeEntry()
                }
            }
        }
        require(replaced) { "JAR resource does not exist: $resourceName" }
        return target
    }

    private fun binding(
        id: UUID,
        pluginId: String,
        enabled: Boolean,
        revision: Long,
        runtimeState: PluginBindingRuntimeState,
    ) = BotPluginBinding(id, pluginId, BotId.parse(BOT), enabled, revision, NOW, NOW, runtimeState, null)

    private fun activeBindingIds(host: Pf4jPluginHost): Set<UUID> {
        val instances = Pf4jPluginHost::class.java.getDeclaredField("instances").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (instances.get(host) as Map<UUID, *>).keys.toSet()
    }

    private fun activeResources(host: Pf4jPluginHost, bindingId: UUID): BindingRuntimeResources {
        val instances = Pf4jPluginHost::class.java.getDeclaredField("instances").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val handle = (instances.get(host) as Map<UUID, *>)[bindingId]!!
        val resources = handle.javaClass.getDeclaredField("resources").apply { isAccessible = true }
        return resources.get(handle) as BindingRuntimeResources
    }

    private fun validationHost(name: String) = Pf4jPluginHost(
        temporaryDirectory.resolve("$name-plugins"),
        temporaryDirectory.resolve("$name-plugin-data"),
        mock(PluginArtifactRepository::class.java),
        mock(BotRepository::class.java),
        mock(OutboxRepository::class.java),
        ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun writeConfiguration(host: Pf4jPluginHost, binding: BotPluginBinding, json: String) {
        Files.createDirectories(host.bindingDataDirectory(binding))
        Files.writeString(host.configurationFile(binding), json)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-19T00:00:00Z")
        const val BOT = "550e8400-e29b-41d4-a716-446655440001"
        const val DEFAULT_CONFIGURATION = "{\"triggerKeyword\":\"/example\",\"replyContent\":\"example reply\"}"
        const val SECOND_CONFIGURATION = "{\"triggerKeyword\":\"hello\",\"replyContent\":\"configured response\"}"
    }
}
