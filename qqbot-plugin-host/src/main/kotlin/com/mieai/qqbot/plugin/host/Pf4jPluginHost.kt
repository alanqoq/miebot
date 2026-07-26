package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.PluginArtifact
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository
import com.mieai.qqbot.plugin.api.ConfigSnapshot
import com.mieai.qqbot.plugin.api.EventService
import com.mieai.qqbot.plugin.api.MediaMessage
import com.mieai.qqbot.plugin.api.MediaService
import com.mieai.qqbot.plugin.api.MessageDeliveryReceipt
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt
import com.mieai.qqbot.plugin.api.MessageSender
import com.mieai.qqbot.plugin.api.PluginContext
import com.mieai.qqbot.plugin.api.PluginLogger
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.api.PluginScheduler
import com.mieai.qqbot.plugin.api.PluginStorage
import com.mieai.qqbot.plugin.api.RichMessage
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import com.mieai.qqbot.plugin.spi.PluginApiVersion
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.regex.Pattern
import org.pf4j.DefaultPluginManager
import org.pf4j.PluginFactory
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory

/** Trusted-JAR PF4J host with binding-scoped resources and atomic in-process upgrades. */
class Pf4jPluginHost(
    pluginDirectory: Path,
    pluginDataRoot: Path,
    private val artifacts: PluginArtifactRepository,
    private val bots: BotRepository,
    private val outbox: OutboxRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val storage: PluginStorageRepository? = null,
    private val mediaStore: MediaAssetStore? = null,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
) : AutoCloseable {
    private val pluginDirectory = pluginDirectory.toAbsolutePath().normalize()
    val pluginDataRoot = pluginDataRoot.toAbsolutePath().normalize()
    private val queueCapacity: Int
    private val shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout")
    private val eventMapper = PluginEventMapper(mapper)
    private val loaded = HashMap<String, LoadedPlugin>()
    private val instances = HashMap<UUID, InstanceHandle>()
    private var manager: DefaultPluginManager? = null
    private var started = false

    init {
        require(queueCapacity in 1..100_000) { "queueCapacity is invalid" }
        this.queueCapacity = queueCapacity
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (!Files.isDirectory(pluginDirectory)) return
        manager = newManager(pluginDirectory)
        val jars = try {
            Files.list(pluginDirectory).use { paths ->
                paths.filter(Files::isRegularFile).filter(::isJar).sorted().toList()
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to list plugin directory", exception)
        }
        for (path in jars) {
            try {
                loadIntoHost(path, false)
            } catch (exception: RuntimeException) {
                LOGGER.warn(
                    "Plugin artifact {} could not be loaded ({})",
                    path.fileName,
                    exception.javaClass.simpleName,
                )
            }
        }
    }

    @Synchronized
    fun reload() {
        stopInternal()
        start()
    }

    fun validateArtifact(candidate: Path): PluginArtifactCandidate {
        val path = normalizedRegularJar(candidate)
        val verifier = newManager(path.parent)
        var pluginId: String? = null
        try {
            val loadedId = verifier.loadPlugin(path)
            pluginId = loadedId
            val validatedId = requirePluginId(loadedId)
            verifier.startPlugin(validatedId)
            val details = details(verifier.getPlugin(validatedId))
            val metadata = details.metadata
            return PluginArtifactCandidate(
                metadata.id,
                metadata.name,
                metadata.version,
                metadata.apiCompatibility,
                path.fileName.toString(),
                metadata.sha256,
                path,
            )
        } finally {
            pluginId?.let { loadedId ->
                try {
                    verifier.stopPlugin(loadedId)
                } catch (_: RuntimeException) {
                    // Best-effort verifier cleanup.
                }
                try {
                    verifier.unloadPlugin(loadedId)
                } catch (_: RuntimeException) {
                    // Best-effort verifier cleanup.
                }
            }
            verifier.unloadPlugins()
        }
    }

    @Synchronized
    fun installArtifact(stagedArtifact: Path): PluginArtifactInstallResult {
        check(started) { "Plugin host is not started" }
        val candidate = validateArtifact(stagedArtifact)
        val previous = loaded[candidate.pluginId]
        if (previous != null && previous.metadata.sha256 == candidate.sha256) {
            deleteQuietly(candidate.path)
            return PluginArtifactInstallResult(
                "UNCHANGED",
                previous.metadata,
                previous.metadata.version,
                previous.metadata.sha256,
            )
        }

        val previousPath = previous?.metadata?.path
        val stoppedInstances = removeInstances(candidate.pluginId)
        stopInstances(stoppedInstances)

        val target = pluginDirectory.resolve(artifactFileName(candidate)).normalize()
        check(target.parent == pluginDirectory) { "Invalid plugin target path" }
        var targetCreated = false
        try {
            if (previous != null) unload(candidate.pluginId)
            Files.createDirectories(pluginDirectory)
            if (Files.exists(target)) {
                check(Files.isRegularFile(target) && candidate.sha256 == sha256(target)) {
                    "Plugin target already exists with different content"
                }
                deleteQuietly(candidate.path)
            } else {
                move(candidate.path, target)
                targetCreated = true
            }
            val installed = loadIntoHost(target, true)
            cleanupSupersededArtifacts(candidate.pluginId, target, previousPath)
            return PluginArtifactInstallResult(
                if (previous == null) "INSTALLED" else "UPGRADED",
                installed.metadata,
                previous?.metadata?.version,
                previous?.metadata?.sha256,
            )
        } catch (failure: Exception) {
            if (targetCreated) deleteQuietly(target)
            if (previousPath != null && Files.isRegularFile(previousPath) && !loaded.containsKey(candidate.pluginId)) {
                try {
                    loadIntoHost(previousPath, true)
                } catch (rollbackFailure: RuntimeException) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            if (failure is RuntimeException) throw failure
            throw IllegalStateException("Unable to install plugin artifact", failure)
        }
    }

    @Synchronized
    fun isStarted(): Boolean = started

    @Synchronized
    fun isLoaded(pluginId: String): Boolean = loaded.containsKey(pluginId)

    @Synchronized
    fun isLoaded(pluginId: String, sha256: String): Boolean = loaded[pluginId]?.metadata?.sha256 == sha256

    @Synchronized
    fun loadedPluginIds(): Set<String> = loaded.keys.toSet()

    /** Hashes currently loaded by this JVM, used for multi-instance lease admission. */
    @Synchronized
    fun loadedPluginHashes(): Map<String, String> = loaded.values.associate {
        it.metadata.id to it.metadata.sha256
    }.toMap()

    @Synchronized
    fun loadedPlugins(): List<LoadedPluginMetadata> = loaded.values.map(LoadedPlugin::metadata).sortedBy { it.id }

    /** Returns the normalized JSON object declared by the plugin artifact as its binding default. */
    @Synchronized
    fun defaultConfiguration(pluginId: String): String {
        val plugin = loaded[requirePluginId(pluginId)]
            ?: throw IllegalStateException("Plugin is not loaded: $pluginId")
        return plugin.metadata.defaultConfiguration
    }

    /** Resolves the private data directory owned by exactly one bot/plugin binding. */
    fun bindingDataDirectory(binding: BotPluginBinding): Path {
        val botDirectory = pluginDataRoot.resolve(binding.botId.toString()).normalize()
        val bindingDirectory = botDirectory.resolve(requirePluginId(binding.pluginId)).normalize()
        require(bindingDirectory.startsWith(pluginDataRoot) && bindingDirectory.parent == botDirectory) {
            "Plugin id does not resolve to one binding directory"
        }
        return bindingDirectory
    }

    /** Resolves the configuration file stored inside one bot/plugin binding directory. */
    fun configurationFile(binding: BotPluginBinding): Path {
        val directory = bindingDataDirectory(binding)
        val configuration = directory.resolve(CONFIGURATION_FILE_NAME).normalize()
        check(configuration.parent == directory) { "Plugin configuration path escaped its binding directory" }
        return configuration
    }

    @Synchronized
    fun validateConfiguration(pluginId: String, configurationJson: String): List<String> {
        val plugin = loaded[pluginId] ?: return listOf("Plugin is not loaded")
        return try {
            val value = mapper.readTree(configurationJson)
            if (value == null || !value.isObject) {
                listOf("${'$'} must be an object")
            } else {
                PluginConfigurationValidator().validate(plugin.schema, value)
            }
        } catch (_: Exception) {
            listOf("Configuration is not valid JSON")
        }
    }

    @Synchronized
    fun handlerIds(binding: BotPluginBinding): List<String> = instance(binding).resources.handlerIds()

    @Synchronized
    fun handlerIds(binding: BotPluginBinding, eventType: String): List<String> =
        instance(binding).resources.handlerIds(eventType)

    /** Stops local instances whose authoritative binding no longer permits the same runtime. */
    @Synchronized
    fun reconcileBindings(currentBindings: List<BotPluginBinding>) {
        val authoritative = HashMap<UUID, BotPluginBinding>()
        for (binding in currentBindings) {
            if (authoritative.put(binding.id, binding) != null) {
                throw IllegalArgumentException("currentBindings contains a duplicate binding id: ${binding.id}")
            }
        }

        val stale = instances.values.filter { handle ->
            val binding = authoritative[handle.bindingId]
            binding == null || !binding.enabled || !binding.runtimeState.runnable() ||
                handle.pluginId != binding.pluginId || handle.revision != binding.revision
        }
        stale.forEach { handle -> instances.remove(handle.bindingId) }
        stopInstances(stale)
    }

    fun execute(binding: BotPluginBinding, inboxEvent: InboxEvent): CompletionStage<Void> {
        val handlerId = synchronized(this) {
            val handle = instance(binding)
            val handlerIds = handle.resources.handlerIds()
            if (handlerIds.isEmpty()) {
                throw IllegalStateException("Plugin has no registered event handlers: ${binding.pluginId}")
            }
            handlerIds.first()
        }
        return execute(binding, inboxEvent, handlerId)
    }

    fun execute(binding: BotPluginBinding, inboxEvent: InboxEvent, handlerId: String): CompletionStage<Void> =
        executeCancellable(binding, inboxEvent, handlerId).stage()

    fun executeCancellable(binding: BotPluginBinding, inboxEvent: InboxEvent, handlerId: String): PluginExecution {
        val handle = synchronized(this) { instance(binding) }
        return handle.resources.executeCancellable(handlerId, eventMapper.map(inboxEvent))
    }

    @Synchronized
    fun invalidate(bindingId: UUID) {
        instances.remove(bindingId)?.let(::stop)
    }

    /** Fences one binding and requires every accepted callback to become idle before returning. */
    @Synchronized
    fun quiesceBindingStrict(bindingId: UUID) {
        val handle = instances[bindingId] ?: return
        handle.resources.beginShutdown()
        if (!handle.resources.awaitIdle(shutdownTimeout)) {
            LOGGER.warn(
                "Plugin {} binding {} did not become idle before strict quiescence",
                handle.pluginId,
                handle.bindingId,
            )
            throw IllegalStateException("Plugin callbacks are still running for binding $bindingId")
        }
        instances.remove(bindingId, handle)
        finishStop(handle)
    }

    /** Fences every local binding for one bot and waits for all accepted callbacks to finish. */
    @Synchronized
    fun invalidateBot(botId: BotId) {
        val handles = instances.values.filter { it.botId == botId }
        handles.forEach { it.resources.beginShutdown() }

        val deadline = System.nanoTime() + shutdownTimeout.toNanos()
        val busy = ArrayList<InstanceHandle>()
        for (handle in handles) {
            val remaining = maxOf(0L, deadline - System.nanoTime())
            if (!handle.resources.awaitIdle(Duration.ofNanos(remaining))) busy.add(handle)
        }
        if (busy.isNotEmpty()) {
            LOGGER.warn("{} plugin binding(s) for bot {} did not become idle before deletion", busy.size, botId)
            throw IllegalStateException("Plugin callbacks are still running for bot $botId")
        }

        handles.forEach { handle -> instances.remove(handle.bindingId, handle) }
        handles.forEach(::finishStop)
    }

    /** Immediately fences a timed-out binding before its durable state is quarantined. */
    @Synchronized
    fun quarantine(bindingId: UUID) {
        val handle = instances.remove(bindingId) ?: return
        handle.resources.beginShutdown()
        handle.resources.close()
        handle.httpClient?.close()
        try {
            handle.plugin.stop()
        } catch (_: RuntimeException) {
            LOGGER.warn("Plugin {} failed while quarantining binding {}", handle.pluginId, handle.bindingId)
        }
    }

    @Synchronized
    fun invalidateAll() {
        val handles = ArrayList(instances.values)
        instances.clear()
        stopInstances(handles)
    }

    private fun instance(binding: BotPluginBinding): InstanceHandle {
        check(started) { "Plugin host is not started" }
        val current = instances[binding.id]
        if (current != null && current.revision == binding.revision && current.pluginId == binding.pluginId) {
            return current
        }
        if (current != null) {
            instances.remove(binding.id)
            stop(current)
        }
        val plugin = loaded[binding.pluginId]
            ?: throw IllegalStateException("Plugin is not loaded: ${binding.pluginId}")
        val dataDirectory = requireBindingDataDirectory(binding)
        val configurationJson = readBindingConfiguration(binding, plugin, dataDirectory)
        val bot = bots.findById(binding.botId)
            ?: throw IllegalStateException("Bot does not exist for plugin binding")
        val environment = bot.definition.environment
        val logger: PluginLogger = BindingLogger(binding.pluginId, binding.botId.toString())
        val resources = BindingRuntimeResources(binding.pluginId, binding.id.toString(), queueCapacity)
        val durable = DurableMessageSender(
            binding.id,
            binding.botId,
            environment,
            outbox,
            mapper,
            clock,
            resources.capabilityGuard,
            mediaStore,
            bot.definition.maxMediaUploadBytes,
        )
        val sender: MessageSender = if (plugin.metadata.capabilities.contains("message.send")) {
            durable
        } else {
            DeniedMessageSender()
        }
        val pluginStorage: PluginStorage = if (
            plugin.metadata.capabilities.contains("storage") && storage != null
        ) {
            DurablePluginStorage(binding.id, storage, clock)
        } else {
            PluginStorage.denied()
        }
        val http = JdkPluginHttpClient()
        val base = PluginContext(
            binding.botId,
            environment,
            binding.pluginId,
            dataDirectory,
            configurationJson,
            sender,
            logger,
            pluginStorage,
        )
        val extended = PluginRuntimeContext(
            base,
            ConfigSnapshot(configurationJson, binding.revision, clock.instant()),
            if (plugin.metadata.capabilities.contains("event.subscribe")) resources.eventService() else EventService.denied(),
            if (plugin.metadata.capabilities.contains("scheduler")) resources.pluginScheduler() else PluginScheduler.denied(),
            http,
            if (plugin.metadata.capabilities.contains("media.send")) durable else MediaService.denied(),
        )
        try {
            val botPlugin = requireNotNull(plugin.factory.create(extended)) { "plugin factory returned null" }
            botPlugin.start()
            val handle = InstanceHandle(
                binding.id,
                binding.botId,
                binding.pluginId,
                binding.revision,
                botPlugin,
                resources,
                http,
            )
            instances[binding.id] = handle
            return handle
        } catch (failure: RuntimeException) {
            resources.close()
            http.close()
            throw failure
        }
    }

    private fun requireBindingDataDirectory(binding: BotPluginBinding): Path {
        val directory = bindingDataDirectory(binding)
        check(Files.isDirectory(pluginDataRoot)) {
            "Plugin data root does not exist or is not a directory: $pluginDataRoot"
        }
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Plugin binding data directory does not exist or is not a directory: $directory"
        }
        try {
            val realRoot = pluginDataRoot.toRealPath()
            val realDirectory = directory.toRealPath()
            check(realDirectory.startsWith(realRoot)) {
                "Plugin binding data directory escapes the configured data root: $directory"
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to resolve plugin binding data directory: $directory", exception)
        }
        return directory
    }

    private fun readBindingConfiguration(
        binding: BotPluginBinding,
        plugin: LoadedPlugin,
        dataDirectory: Path,
    ): String {
        val configuration = configurationFile(binding)
        check(Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)) {
            "Plugin binding configuration file does not exist or is not a regular file: $configuration"
        }
        try {
            val realDirectory = dataDirectory.toRealPath()
            val realConfiguration = configuration.toRealPath()
            check(realConfiguration.parent == realDirectory) {
                "Plugin binding configuration file escapes its data directory: $configuration"
            }
            val json = readBindingConfigurationUtf8(configuration)
            val value = try {
                mapper.readTree(json)
            } catch (exception: Exception) {
                throw IllegalStateException("Plugin binding configuration is not valid JSON: $configuration", exception)
            }
            check(value != null && value.isObject) {
                "Plugin binding configuration must be a JSON object: $configuration"
            }
            val errors = PluginConfigurationValidator().validate(plugin.schema, value)
            check(errors.isEmpty()) {
                "Plugin binding configuration failed schema validation: ${errors.joinToString("; ")}"
            }
            return json
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to read plugin binding configuration: $configuration", exception)
        }
    }

    private fun loadIntoHost(path: Path, authoritative: Boolean): LoadedPlugin {
        ensureManager()
        val activeManager = checkNotNull(manager)
        val pluginId = activeManager.loadPlugin(path)
        try {
            val validatedId = requirePluginId(pluginId)
            activeManager.startPlugin(validatedId)
            val details = details(activeManager.getPlugin(validatedId))
            val plugin = LoadedPlugin(details.metadata, details.factory, details.schema)
            loaded[validatedId] = plugin
            persist(plugin.metadata, authoritative)
            return plugin
        } catch (exception: RuntimeException) {
            try {
                activeManager.stopPlugin(pluginId)
            } catch (_: RuntimeException) {
                // Best-effort rollback.
            }
            try {
                activeManager.unloadPlugin(pluginId)
            } catch (_: RuntimeException) {
                // Best-effort rollback.
            }
            loaded.remove(pluginId)
            throw exception
        }
    }

    private fun details(wrapper: PluginWrapper?): LoadedPluginDetails {
        val bridge = wrapper?.plugin as? Pf4jPluginBridge
            ?: throw IllegalStateException("Plugin does not use the QQBot PF4J bridge")
        val pluginId = requirePluginId(wrapper.pluginId)
        val factory = bridge.pluginFactory
        check(pluginId == factory.pluginId) { "Plugin descriptor id does not match factory id" }
        val path = wrapper.pluginPath.toAbsolutePath().normalize()
        var name = pluginId
        var api = wrapper.descriptor.requires
        if (api.isNullOrBlank()) api = PluginApiVersion.CURRENT
        check(api == PluginApiVersion.CURRENT) {
            "Plugin $pluginId requires API $api, but this host requires ${PluginApiVersion.CURRENT}"
        }
        var schemaPath: String? = null
        var defaultConfigurationPath: String? = null
        val capabilities = HashSet<String>()
        try {
            JarFile(path.toFile(), false).use { jar ->
                val manifest = jar.manifest
                if (manifest != null) {
                    val attributes = manifest.mainAttributes
                    attributes.getValue("Plugin-Name")?.takeIf { it.isNotBlank() }?.let { name = it.trim() }
                    schemaPath = attributes.getValue("Plugin-Config-Schema")
                    defaultConfigurationPath = attributes.getValue("Plugin-Default-Config")
                    attributes.getValue("Plugin-Capabilities")?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotBlank)
                        ?.forEach(capabilities::add)
                }
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to inspect loaded plugin", exception)
        }
        check(!schemaPath.isNullOrBlank()) { "Plugin manifest must declare Plugin-Config-Schema" }
        check(!defaultConfigurationPath.isNullOrBlank()) {
            "Plugin manifest must declare Plugin-Default-Config"
        }
        val normalizedSchemaPath = requireResourcePath(requireNotNull(schemaPath), "Plugin-Config-Schema")
        val normalizedDefaultPath = requireResourcePath(
            requireNotNull(defaultConfigurationPath),
            "Plugin-Default-Config",
        )
        capabilities.add("event.read")
        capabilities.add("http")
        for (capability in capabilities) {
            check(SUPPORTED_CAPABILITIES.contains(capability)) { "Unsupported plugin capability: $capability" }
        }
        val schema = readPluginSchema(path, normalizedSchemaPath)
        check(schema != null && schema.isObject) { "Plugin configuration schema must be an object" }
        val defaultConfiguration = readPluginDefaultConfiguration(path, normalizedDefaultPath)
        check(defaultConfiguration != null && defaultConfiguration.isObject) {
            "Plugin default configuration must be a JSON object"
        }
        val defaultErrors = PluginConfigurationValidator().validate(schema, defaultConfiguration)
        check(defaultErrors.isEmpty()) {
            "Plugin default configuration failed schema validation: ${defaultErrors.joinToString("; ")}"
        }
        val normalizedDefault = try {
            mapper.writeValueAsString(defaultConfiguration)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to normalize plugin default configuration", exception)
        }
        val metadata = LoadedPluginMetadata(
            pluginId,
            name,
            wrapper.descriptor.version,
            api,
            path,
            sha256(path),
            factory.javaClass.name,
            normalizedSchemaPath,
            normalizedDefaultPath,
            normalizedDefault,
            capabilities.toSet(),
        )
        return LoadedPluginDetails(metadata, factory, schema)
    }

    private fun readPluginSchema(pluginPath: Path, resourcePath: String): JsonNode? = try {
        JarFile(pluginPath.toFile(), false).use { jar ->
            val entry = requirePluginResource(jar, resourcePath, "Plugin configuration schema resource is missing")
            jar.getInputStream(entry).use(mapper::readTree)
        }
    } catch (exception: IOException) {
        throw IllegalStateException("Unable to read plugin configuration schema", exception)
    }

    private fun readPluginDefaultConfiguration(pluginPath: Path, resourcePath: String): JsonNode? = try {
        JarFile(pluginPath.toFile(), false).use { jar ->
            val entry = requirePluginResource(jar, resourcePath, "Plugin default configuration resource is missing")
            jar.getInputStream(entry).use { input ->
                val bytes = input.readNBytes(MAX_CONFIGURATION_BYTES + 1)
                check(bytes.size <= MAX_CONFIGURATION_BYTES) {
                    "Plugin default configuration cannot exceed 64 KiB"
                }
                mapper.readTree(bytes)
            }
        }
    } catch (exception: IOException) {
        throw IllegalStateException("Unable to read plugin default configuration", exception)
    }

    private fun persist(metadata: LoadedPluginMetadata, authoritative: Boolean) {
        val now = clock.instant()
        val previous = artifacts.findById(metadata.id)
        if (!authoritative && previous != null && previous.sha256 != metadata.sha256) {
            LOGGER.warn(
                "Plugin {} hash {} differs from catalog hash {}; this instance will not own matching bots",
                metadata.id,
                metadata.sha256,
                previous.sha256,
            )
            return
        }
        artifacts.upsert(
            PluginArtifact(
                metadata.id,
                metadata.name,
                metadata.version,
                metadata.apiCompatibility,
                metadata.path.fileName.toString(),
                metadata.sha256,
                metadata.entrypoint,
                "LOADED",
                true,
                previous?.createdAt ?: now,
                now,
            ),
        )
    }

    private fun unload(pluginId: String) {
        removeInstances(pluginId).forEach(::stop)
        val activeManager = manager
        if (activeManager != null && activeManager.getPlugin(pluginId) != null) {
            activeManager.stopPlugin(pluginId)
            if (!activeManager.unloadPlugin(pluginId)) {
                try {
                    activeManager.startPlugin(pluginId)
                } catch (restartFailure: RuntimeException) {
                    loaded.remove(pluginId)
                    throw IllegalStateException("Unable to unload or restore plugin $pluginId", restartFailure)
                }
                throw IllegalStateException("Unable to unload plugin $pluginId")
            }
        }
        loaded.remove(pluginId)
    }

    private fun removeInstances(pluginId: String): List<InstanceHandle> {
        val removed = instances.values.filter { it.pluginId == pluginId }
        removed.forEach { instances.remove(it.bindingId) }
        return removed
    }

    private fun stopInstances(handles: List<InstanceHandle>) = handles.forEach(::stop)

    private fun stop(handle: InstanceHandle) {
        handle.resources.beginShutdown()
        if (!handle.resources.awaitIdle(shutdownTimeout)) {
            LOGGER.warn(
                "Plugin {} binding {} did not become idle before shutdown",
                handle.pluginId,
                handle.bindingId,
            )
        }
        finishStop(handle)
    }

    private fun finishStop(handle: InstanceHandle) {
        try {
            handle.plugin.stop()
        } catch (_: RuntimeException) {
            LOGGER.warn("Plugin {} failed while stopping binding {}", handle.pluginId, handle.bindingId)
        } finally {
            handle.resources.close()
            handle.httpClient?.close()
        }
    }

    @Synchronized
    override fun close() {
        stopInternal()
    }

    private fun stopInternal() {
        invalidateAll()
        manager?.let { activeManager ->
            activeManager.stopPlugins()
            activeManager.unloadPlugins()
        }
        manager = null
        loaded.clear()
        started = false
    }

    private fun ensureManager() {
        if (manager == null) manager = newManager(pluginDirectory)
    }

    private fun cleanupSupersededArtifacts(pluginId: String, target: Path, previousPath: Path?) {
        if (previousPath != null && previousPath != target) deleteQuietly(previousPath)
        try {
            Files.list(pluginDirectory).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter(::isJar)
                    .filter { it != target }
                    .filter { pluginId == manifestPluginId(it) }
                    .forEach(::deleteQuietly)
            }
        } catch (_: IOException) {
            LOGGER.warn("Unable to clean superseded artifacts for {}", pluginId)
        }
    }

    private data class LoadedPlugin(
        val metadata: LoadedPluginMetadata,
        val factory: BotPluginFactory,
        val schema: JsonNode,
    )

    private data class LoadedPluginDetails(
        val metadata: LoadedPluginMetadata,
        val factory: BotPluginFactory,
        val schema: JsonNode,
    )

    private data class InstanceHandle(
        val bindingId: UUID,
        val botId: BotId,
        val pluginId: String,
        val revision: Long,
        val plugin: BotPlugin,
        val resources: BindingRuntimeResources,
        val httpClient: JdkPluginHttpClient?,
    )

    private class BindingLogger(pluginId: String, botId: String) : PluginLogger {
        private val prefix = "plugin=$pluginId bot=$botId "

        override fun info(message: String) {
            LOGGER.info("{}{}", prefix, sanitize(message))
        }

        override fun warn(message: String) {
            LOGGER.warn("{}{}", prefix, sanitize(message))
        }

        override fun error(message: String, cause: Throwable) {
            LOGGER.error("{}{} ({})", prefix, sanitize(message), cause.javaClass.simpleName)
        }
    }

    private class DeniedMessageSender : MessageSender {
        override fun enqueue(message: TextMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

        override fun enqueue(message: MediaMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

        override fun enqueue(message: RichMessage): CompletionStage<MessageEnqueueReceipt> = deniedResult()

        override fun findDelivery(jobId: UUID): CompletionStage<MessageDeliveryReceipt?> = deniedResult()

        private fun <T> deniedResult(): CompletionStage<T> =
            CompletableFuture.failedFuture(SecurityException("Plugin does not have message.send capability"))
    }

    private class DurablePluginStorage(
        private val bindingId: UUID,
        private val repository: PluginStorageRepository,
        private val clock: Clock,
    ) : PluginStorage {
        override fun get(namespace: String, key: String): String? = repository.find(bindingId, namespace, key)

        override fun put(namespace: String, key: String, value: String) {
            repository.put(bindingId, namespace, key, value, clock.instant())
        }

        override fun delete(namespace: String, key: String) {
            repository.delete(bindingId, namespace, key)
        }

        override fun list(namespace: String): Map<String, String> =
            repository.list(bindingId, namespace).toMap()
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(Pf4jPluginHost::class.java)
        val SUPPORTED_CAPABILITIES = setOf(
            "event.read",
            "event.subscribe",
            "message.send",
            "media.send",
            "storage",
            "scheduler",
            "http",
        )
        const val DEFAULT_QUEUE_CAPACITY = 256
        val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(20)
        const val CONFIGURATION_FILE_NAME = "config.json"
        const val MAX_CONFIGURATION_BYTES = 64 * 1024
        val PLUGIN_ID_PATTERN: Pattern = Pattern.compile("[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?")
        val WINDOWS_RESERVED_PLUGIN_ID_STEMS = setOf(
            "con",
            "prn",
            "aux",
            "nul",
            "com1",
            "com2",
            "com3",
            "com4",
            "com5",
            "com6",
            "com7",
            "com8",
            "com9",
            "lpt1",
            "lpt2",
            "lpt3",
            "lpt4",
            "lpt5",
            "lpt6",
            "lpt7",
            "lpt8",
            "lpt9",
        )

        fun readBindingConfigurationUtf8(configuration: Path): String {
            val bytes = Files.newInputStream(configuration).use { input ->
                input.readNBytes(MAX_CONFIGURATION_BYTES + 1)
            }
            check(bytes.size <= MAX_CONFIGURATION_BYTES) {
                "Plugin binding configuration cannot exceed 64 KiB: $configuration"
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (exception: CharacterCodingException) {
                throw IllegalStateException(
                    "Plugin binding configuration must be valid UTF-8: $configuration",
                    exception,
                )
            }
        }

        fun requirePluginResource(jar: JarFile, resourcePath: String, missingMessage: String): JarEntry {
            val entry = jar.getJarEntry(resourcePath)
            check(entry != null && !entry.isDirectory) { missingMessage }
            return entry
        }

        fun newManager(root: Path): DefaultPluginManager = QqBotPluginManager(root).apply {
            setSystemVersion(PluginApiVersion.CURRENT)
        }

        private class QqBotPluginManager(root: Path) : DefaultPluginManager(root) {
            override fun createPluginFactory(): PluginFactory = PluginFactory { wrapper ->
                require(wrapper.descriptor.pluginClass == Pf4jPluginBridge::class.java.name) {
                    "Plugin-Class must be ${Pf4jPluginBridge::class.java.name}"
                }
                Pf4jPluginBridge(wrapper.pluginClassLoader)
            }
        }

        fun manifestPluginId(path: Path): String? = try {
            JarFile(path.toFile(), false).use { jar -> jar.manifest?.mainAttributes?.getValue("Plugin-Id") }
        } catch (_: IOException) {
            null
        }

        fun normalizedRegularJar(value: Path): Path {
            val path = value.toAbsolutePath().normalize()
            require(Files.isRegularFile(path) && isJar(path)) { "candidate must be a JAR file" }
            return path
        }

        fun isJar(path: Path): Boolean = path.fileName.toString().lowercase(Locale.ROOT).endsWith(".jar")

        fun artifactFileName(candidate: PluginArtifactCandidate): String =
            "${safe(candidate.pluginId)}-${safe(candidate.version)}-${candidate.sha256.substring(0, 12)}.jar"

        fun safe(value: String): String {
            val safe = value.replace(Regex("[^A-Za-z0-9._-]"), "-")
            return if (safe.isBlank()) "plugin" else safe
        }

        @Throws(IOException::class)
        fun move(source: Path, target: Path) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target)
            }
        }

        fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                LOGGER.warn("Unable to delete plugin artifact {}", path.fileName)
            }
        }

        fun sha256(path: Path): String = try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            HexFormat.of().formatHex(digest.digest())
        } catch (exception: Exception) {
            throw IllegalStateException("Unable to hash plugin artifact", exception)
        }

        fun requirePositive(value: Duration, name: String): Duration {
            require(!value.isZero && !value.isNegative) { "$name must be positive" }
            return value
        }

        fun requirePluginId(pluginId: String?): String {
            require(pluginId != null && PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
                "Plugin id must be 1-128 lowercase ASCII letters, digits, dots, underscores or hyphens, " +
                    "and must start and end with a letter or digit"
            }
            val dot = pluginId.indexOf('.')
            val windowsStem = if (dot < 0) pluginId else pluginId.substring(0, dot)
            require(!WINDOWS_RESERVED_PLUGIN_ID_STEMS.contains(windowsStem)) {
                "Plugin id uses a reserved Windows directory name: $pluginId"
            }
            return pluginId
        }

        fun requireResourcePath(value: String, attribute: String): String {
            val path = value.trim()
            check(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\')) {
                "$attribute must name a relative JAR resource"
            }
            for (segment in path.split('/')) {
                check(segment.isNotBlank() && segment != "." && segment != "..") {
                    "$attribute must name a normalized JAR resource"
                }
            }
            return path
        }

        fun sanitize(value: String?): String {
            if (value == null) return ""
            val clean = value.replace('\n', ' ').replace('\r', ' ')
            return if (clean.length > 512) clean.substring(0, 512) else clean
        }
    }
}
