package com.mieai.qqbot.admin.plugins

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginBindingOptimisticLockException
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class PluginBindingFileService(
    private val bindings: BotPluginBindingRepository,
    private val host: Pf4jPluginHost,
    private val runtime: PluginRuntimeService,
    private val mapper: ObjectMapper,
) {
    private val clock = Clock.systemUTC()

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PluginBindingFileService::class.java)
        private const val CONFIGURATION_FILE = "config.json"
        private const val MAX_CONFIGURATION_BYTES = 65_536L
        private const val MAX_TEXT_BYTES = 2L * 1024L * 1024L
        private const val CONFIGURATION_ERROR = "Plugin configuration file is missing or invalid"
        private const val INTERNAL_LOCK_DIRECTORY = ".locks"
        private val SHA_256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        private val TOMBSTONE_PATTERN = Regex(
            "^\\.tombstone-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})-" +
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
        private val BOT_TOMBSTONE_PATTERN = Regex(
            "^\\.bot-tombstone-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})-" +
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
        private val LOCAL_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
    }

    fun initializeMissingBindings() {
        val unresolvedTombstones = recoverTombstones()
        bindings.findAll().filterNot { it.id in unresolvedTombstones }.forEach { binding ->
            try {
                withBindingLock(binding.id) {
                    requireRoot(binding)
                    val configuration = host.configurationFile(binding)
                    if (!Files.exists(configuration, LinkOption.NOFOLLOW_LINKS)) {
                        writeAtomic(
                            configuration,
                            host.defaultConfiguration(binding.pluginId).toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    requireValidConfigurationUnlocked(binding)
                }
            } catch (exception: Exception) {
                val message = "$CONFIGURATION_ERROR: ${exception.javaClass.simpleName}"
                bindings.setRuntimeState(
                    binding.id,
                    PluginBindingRuntimeState.QUARANTINED,
                    message,
                    clock.instant(),
                )
                LOGGER.warn(
                    "Unable to initialize plugin data for plugin={} bot={} ({})",
                    binding.pluginId,
                    binding.botId,
                    exception.javaClass.simpleName,
                )
            }
        }
    }

    fun normalizedConfiguration(pluginId: String, value: String): String {
        try {
            val parsed = mapper.readTree(value)
            if (parsed == null || !parsed.isObject) {
                throw failure(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG", "Plugin configuration must be a JSON object")
            }
            val normalized = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed)
            val violations = host.validateConfiguration(pluginId, normalized)
            if (violations.isNotEmpty()) {
                throw failure(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG", violations.first())
            }
            if (normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_CONFIGURATION_BYTES) {
                throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_CONFIG_TOO_LARGE", "Plugin configuration cannot exceed 64 KiB")
            }
            return normalized
        } catch (exception: JsonProcessingException) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG", "Plugin configuration is not valid JSON")
        }
    }

    fun initialize(binding: BotPluginBinding, configurationJson: String) {
        withBindingDataLock(binding) {
            requireBindingOwnership(binding)
            val root = requireRoot(binding)
            try {
                deleteTree(root)
                Files.createDirectories(root)
                writeAtomic(root.resolve(CONFIGURATION_FILE), configurationJson.toByteArray(StandardCharsets.UTF_8))
                if (!bindingOwnedBy(binding)) {
                    deleteTree(root)
                    removeEmptyParents(root.parent, host.pluginDataRoot.toAbsolutePath().normalize())
                    throw failure(
                        HttpStatus.CONFLICT,
                        "BINDING_CREATION_ABORTED",
                        "Plugin binding disappeared while its data directory was initialized",
                    )
                }
            } catch (exception: IOException) {
                throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_INITIALIZATION_FAILED", "Unable to initialize plugin data directory")
            }
        }
    }

    fun requireValidConfiguration(binding: BotPluginBinding): String = withBindingDataLock(binding) {
        requireValidConfigurationUnlocked(binding)
    }

    private fun requireValidConfigurationUnlocked(binding: BotPluginBinding): String {
        requireExistingRoot(binding)
        val file = host.configurationFile(binding)
        val value = try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_CONFIG_MISSING", "Plugin configuration file does not exist")
            }
            if (Files.size(file) > MAX_CONFIGURATION_BYTES) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_CONFIG_TOO_LARGE", "Plugin configuration cannot exceed 64 KiB")
            }
            decodeUtf8(
                Files.readAllBytes(file),
                HttpStatus.CONFLICT,
                "PLUGIN_CONFIG_ENCODING_INVALID",
                "Plugin configuration must use valid UTF-8",
            )
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_CONFIG_READ_FAILED", "Unable to read plugin configuration")
        }
        return normalizedConfiguration(binding.pluginId, value)
    }

    fun list(bindingId: UUID, relativeDirectory: String): PluginFileListingResponse {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val directory = resolve(root, relativeDirectory, allowEmpty = true, allowFinalSymbolicLink = false)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_DIRECTORY_NOT_FOUND", "Plugin directory does not exist")
        }
        return try {
            val entries = Files.list(directory).use { paths ->
                paths.map { entry(root, it) }
                    .sorted(
                        compareByDescending<PluginFileEntryResponse> { it.directory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                    .toList()
            }
            PluginFileListingResponse(relative(root, directory), entries)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DIRECTORY_READ_FAILED", "Unable to read plugin directory")
        }
    }

    fun content(bindingId: UUID, relativeFile: String): PluginFileContentResponse {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val file = resolve(root, relativeFile, allowEmpty = false, allowFinalSymbolicLink = false)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_FILE_NOT_FOUND", "Plugin file does not exist")
        }
        return try {
            if (Files.size(file) > MAX_TEXT_BYTES) {
                throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_FILE_PREVIEW_TOO_LARGE", "Text preview cannot exceed 2 MiB")
            }
            val bytes = Files.readAllBytes(file)
            PluginFileContentResponse(
                relative(root, file),
                decodeUtf8(
                    bytes,
                    HttpStatus.CONFLICT,
                    "PLUGIN_FILE_NOT_UTF8",
                    "Plugin file is not valid UTF-8 text",
                ),
                sha256(bytes),
                Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant(),
            )
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_READ_FAILED", "Unable to read plugin file")
        }
    }

    fun saveContent(bindingId: UUID, request: UpdatePluginFileContentRequest): PluginFileContentResponse {
        return withBindingMutationLock(bindingId) {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val file = resolve(root, request.path, allowEmpty = false, allowFinalSymbolicLink = false)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_NOT_REGULAR", "Target is not a regular file")
        }
        if (!Files.isDirectory(file.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_DIRECTORY_NOT_FOUND", "Parent directory does not exist")
        }
        request.expectedSha256?.takeIf { it.isNotBlank() }?.let { expected ->
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_CHANGED", "Plugin file no longer exists")
            }
            val current = try { sha256(file) } catch (exception: IOException) {
                throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_READ_FAILED", "Unable to read plugin file")
            }
            if (!current.equals(expected, ignoreCase = true)) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_CHANGED", "Plugin file changed after it was opened")
            }
        }

        val isConfiguration = relative(root, file) == CONFIGURATION_FILE
        val content = if (isConfiguration) {
            normalizedConfiguration(binding.pluginId, request.content)
        } else {
            request.content
        }
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        validateSavedContentSize(isConfiguration, contentBytes.size)
        if (!isConfiguration) validateJsonFile(file, content)
        val touched = reserveMutation(binding)
        try {
            writeAtomic(file, contentBytes)
        } catch (exception: IOException) {
            val failure = failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_WRITE_FAILED", "Unable to save plugin file")
            convergeAfterFailedMutation(binding.id, failure)
            throw failure
        }
        afterMutation(touched, isConfiguration, configurationValid = true)
        content(bindingId, request.path)
        }
    }

    fun createEntry(bindingId: UUID, request: CreatePluginFileEntryRequest): PluginFileEntryResponse {
        return withBindingMutationLock(bindingId) {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val target = resolve(root, request.path, allowEmpty = false, allowFinalSymbolicLink = false)
        if (!Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_DIRECTORY_NOT_FOUND", "Parent directory does not exist")
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_EXISTS", "Plugin file already exists")
        }
        val isConfiguration = relative(root, target) == CONFIGURATION_FILE
        if (isConfiguration && request.directory) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_CONFIG_MUST_BE_FILE", "Plugin configuration must be a regular file")
        }
        val configuration = if (isConfiguration) {
            normalizedConfiguration(binding.pluginId, host.defaultConfiguration(binding.pluginId))
                .toByteArray(StandardCharsets.UTF_8)
        } else {
            null
        }
        val touched = reserveMutation(binding)
        try {
            if (request.directory) {
                Files.createDirectory(target)
            } else if (configuration != null) {
                writeAtomicNew(target, configuration)
            } else {
                Files.createFile(target)
            }
        } catch (exception: java.nio.file.FileAlreadyExistsException) {
            val failure = failure(HttpStatus.CONFLICT, "PLUGIN_FILE_EXISTS", "Plugin file already exists")
            convergeAfterFailedMutation(binding.id, failure)
            throw failure
        } catch (exception: IOException) {
            val failure = failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_CREATE_FAILED", "Unable to create plugin file")
            convergeAfterFailedMutation(binding.id, failure)
            throw failure
        }
        afterMutation(touched, isConfiguration, configurationValid = isConfiguration)
        entry(root, target)
        }
    }

    fun upload(
        bindingId: UUID,
        relativeDirectory: String,
        file: MultipartFile,
        overwrite: Boolean = false,
        expectedSha256: String? = null,
    ): PluginFileEntryResponse {
        return withBindingMutationLock(bindingId) {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val directory = resolve(root, relativeDirectory, allowEmpty = true, allowFinalSymbolicLink = false)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_DIRECTORY_NOT_FOUND", "Plugin directory does not exist")
        }
        val name = safeUploadName(file.originalFilename)
        val target = resolve(root, relative(root, directory.resolve(name)), allowEmpty = false, allowFinalSymbolicLink = false)
        val expectedHash = normalizedUploadHash(overwrite, expectedSha256)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_NOT_REGULAR", "Target is not a regular file")
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_EXISTS", "Plugin file already exists")
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && expectedHash != null) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_CHANGED", "Plugin file no longer exists")
        }
        val temporary = try { Files.createTempFile("qqbot-plugin-upload-", ".tmp") } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_UPLOAD_FAILED", "Unable to stage plugin file")
        }
        try {
            try {
                BufferedInputStream(file.inputStream).use { input ->
                    BufferedOutputStream(
                        Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                    ).use { output -> input.copyTo(output) }
                }
            } catch (exception: IOException) {
                throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_UPLOAD_FAILED", "Unable to stage plugin file")
            }
            val isConfiguration = relative(root, target) == CONFIGURATION_FILE
            val configuration = if (isConfiguration) {
                if (Files.size(temporary) > MAX_CONFIGURATION_BYTES) {
                    throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_CONFIG_TOO_LARGE", "Plugin configuration cannot exceed 64 KiB")
                }
                val normalized = normalizedConfiguration(
                    binding.pluginId,
                    decodeUtf8(
                        Files.readAllBytes(temporary),
                        HttpStatus.BAD_REQUEST,
                        "PLUGIN_CONFIG_ENCODING_INVALID",
                        "Plugin configuration must use valid UTF-8",
                    ),
                )
                normalized.toByteArray(StandardCharsets.UTF_8)
            } else {
                null
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_NOT_REGULAR", "Target is not a regular file")
            }
            if (!overwrite && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_EXISTS", "Plugin file already exists")
            }
            expectedHash?.let { expected ->
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_CHANGED", "Plugin file no longer exists")
                }
                val current = try { sha256(target) } catch (exception: IOException) {
                    throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_READ_FAILED", "Unable to read plugin file")
                }
                if (!current.equals(expected, ignoreCase = true)) {
                    throw failure(HttpStatus.CONFLICT, "PLUGIN_FILE_CHANGED", "Plugin file changed after overwrite was confirmed")
                }
            }
            val touched = reserveMutation(binding)
            try {
                if (configuration != null) {
                    if (overwrite) writeAtomic(target, configuration) else writeAtomicNew(target, configuration)
                } else {
                    if (overwrite) copyAtomic(temporary, target) else copyAtomicNew(temporary, target)
                }
            } catch (exception: java.nio.file.FileAlreadyExistsException) {
                val failure = failure(HttpStatus.CONFLICT, "PLUGIN_FILE_EXISTS", "Plugin file already exists")
                convergeAfterFailedMutation(binding.id, failure)
                throw failure
            } catch (exception: IOException) {
                val failure = failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_UPLOAD_FAILED", "Unable to upload plugin file")
                convergeAfterFailedMutation(binding.id, failure)
                throw failure
            }
            afterMutation(touched, isConfiguration, configurationValid = true)
            entry(root, target)
        } catch (exception: PluginAdministrationException) {
            throw exception
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_UPLOAD_FAILED", "Unable to upload plugin file")
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
        }
    }

    fun download(bindingId: UUID, relativeFile: String): PluginFileDownload {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val file = resolve(root, relativeFile, allowEmpty = false, allowFinalSymbolicLink = false)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_FILE_NOT_FOUND", "Plugin file does not exist")
        }
        return PluginFileDownload(file, file.fileName.toString(), contentType(file))
    }

    fun delete(bindingId: UUID, relativePath: String) {
        withBindingMutationLock(bindingId) {
        val binding = binding(bindingId)
        val root = requireExistingRoot(binding)
        val target = resolve(root, relativePath, allowEmpty = false, allowFinalSymbolicLink = true)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_FILE_NOT_FOUND", "Plugin file does not exist")
        }
        val isConfiguration = relative(root, target) == CONFIGURATION_FILE
        val touched = reserveMutation(binding)
        try {
            deleteTree(target)
        } catch (exception: IOException) {
            val failure = failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_DELETE_FAILED", "Unable to delete plugin file")
            convergeAfterFailedMutation(binding.id, failure)
            throw failure
        }
        afterMutation(touched, isConfiguration, configurationValid = false)
        }
    }

    fun <T> withDataDirectoryTombstoned(binding: BotPluginBinding, action: () -> T): T {
        return withBindingDataMutationLock(binding) {
        val root = validatedRoot(binding, createIfMissing = false)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return@withBindingDataMutationLock action()
        val dataRoot = host.pluginDataRoot.toAbsolutePath().normalize()
        val tombstone = dataRoot.resolve(".tombstone-${binding.id}-${UUID.randomUUID()}")
        try {
            Files.move(root, tombstone, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_TOMBSTONE_FAILED", "Unable to isolate plugin data directory for deletion")
        }

        val result = try {
            action()
        } catch (exception: Throwable) {
            try {
                Files.move(tombstone, root, StandardCopyOption.ATOMIC_MOVE)
            } catch (restoreFailure: IOException) {
                exception.addSuppressed(restoreFailure)
            }
            throw exception
        }

        try {
            deleteTree(tombstone)
            removeEmptyParents(root.parent, dataRoot)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_DELETE_FAILED", "Unable to delete plugin data tombstone")
        }
        result
        }
    }

    fun deleteBotData(botId: BotId) {
        withBotLock(botId) {
            tombstoneBotDirectory(botId)
        }
    }

    private fun afterMutation(touched: BotPluginBinding, configuration: Boolean, configurationValid: Boolean) {
        if (configuration && !configurationValid) {
            bindings.setRuntimeState(
                touched.id,
                PluginBindingRuntimeState.QUARANTINED,
                CONFIGURATION_ERROR,
                clock.instant(),
            )
            runtime.bindingChanged(touched.id)
            return
        }
        if (configuration && touched.runtimeState == PluginBindingRuntimeState.QUARANTINED &&
            touched.runtimeError.orEmpty().startsWith(CONFIGURATION_ERROR)
        ) {
            runtime.resetQuarantinedBinding(touched.id)
        } else {
            runtime.bindingChanged(touched.id)
        }
    }

    private fun recoverTombstones(): Set<UUID> {
        val dataRoot = host.pluginDataRoot.toAbsolutePath().normalize()
        if (!Files.exists(dataRoot, LinkOption.NOFOLLOW_LINKS)) return emptySet()
        val tombstones = try {
            Files.list(dataRoot).use { paths ->
                paths.filter {
                    TOMBSTONE_PATTERN.matches(it.fileName.toString()) ||
                        BOT_TOMBSTONE_PATTERN.matches(it.fileName.toString())
                }.toList()
            }
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_RECOVERY_FAILED", "Unable to inspect plugin data tombstones")
        }
        val unresolved = mutableSetOf<UUID>()
        tombstones.forEach { tombstone ->
            val botMatch = BOT_TOMBSTONE_PATTERN.matchEntire(tombstone.fileName.toString())
            if (botMatch != null) {
                val botId = BotId.parse(botMatch.groupValues[1])
                try {
                    withBotLock(botId) {
                        if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) deleteTree(tombstone)
                        Unit
                    }
                } catch (exception: Exception) {
                    LOGGER.warn("Unable to clean plugin bot-data tombstone for bot={} ({})", botId, exception.javaClass.simpleName)
                }
                return@forEach
            }
            val match = TOMBSTONE_PATTERN.matchEntire(tombstone.fileName.toString()) ?: return@forEach
            val bindingId = UUID.fromString(match.groupValues[1])
            val recoveryOwner = bindings.findById(bindingId)
            try {
                if (recoveryOwner == null) {
                    if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) deleteTree(tombstone)
                } else withBindingDataLock(recoveryOwner) {
                    val binding = bindings.findById(bindingId)
                    if (binding == null) {
                        if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) deleteTree(tombstone)
                    } else {
                        val root = validatedRoot(binding, createIfMissing = false)
                        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                            throw failure(
                                HttpStatus.CONFLICT,
                                "PLUGIN_DATA_RECOVERY_CONFLICT",
                                "Plugin data directory and deletion tombstone both exist",
                            )
                        }
                        Files.move(tombstone, root, StandardCopyOption.ATOMIC_MOVE)
                    }
                    Unit
                }
            } catch (exception: Exception) {
                if (bindings.findById(bindingId) != null) {
                    unresolved += bindingId
                    bindings.setRuntimeState(
                        bindingId,
                        PluginBindingRuntimeState.QUARANTINED,
                        "$CONFIGURATION_ERROR: tombstone recovery failed",
                        clock.instant(),
                    )
                }
                LOGGER.warn("Unable to recover plugin data tombstone for binding={} ({})", bindingId, exception.javaClass.simpleName)
            }
        }
        return unresolved
    }

    private fun tombstoneBotDirectory(botId: BotId) {
        val dataRoot = host.pluginDataRoot.toAbsolutePath().normalize()
        val botRoot = dataRoot.resolve(botId.toString()).normalize()
        if (botRoot.parent != dataRoot) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_DIRECTORY_INVALID", "Bot plugin data directory is invalid")
        }
        if (!Files.exists(botRoot, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(botRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(botRoot)) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_DIRECTORY_INVALID", "Bot plugin data path is not a directory")
        }
        val tombstone = dataRoot.resolve(".bot-tombstone-${botId}-${UUID.randomUUID()}")
        try {
            Files.move(botRoot, tombstone, StandardCopyOption.ATOMIC_MOVE)
            deleteTree(tombstone)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_DELETE_FAILED", "Unable to delete bot plugin data")
        }
    }

    private fun reserveMutation(binding: BotPluginBinding): BotPluginBinding = try {
        bindings.touch(binding.id, binding.revision, clock.instant())
    } catch (exception: PluginBindingOptimisticLockException) {
        throw failure(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.message ?: "Binding revision conflict")
    }

    private fun convergeAfterFailedMutation(bindingId: UUID, failure: RuntimeException) {
        try {
            runtime.bindingChanged(bindingId)
        } catch (convergenceFailure: RuntimeException) {
            failure.addSuppressed(convergenceFailure)
            LOGGER.warn(
                "Unable to reconcile plugin runtime after a failed file mutation for binding={} ({})",
                bindingId,
                convergenceFailure.javaClass.simpleName,
            )
        }
    }

    private fun <T> withBindingLock(bindingId: UUID, action: () -> T): T {
        val binding = binding(bindingId)
        return withBindingDataLock(binding, action)
    }

    private fun <T> withBindingMutationLock(bindingId: UUID, action: () -> T): T {
        val binding = binding(bindingId)
        return withBindingDataMutationLock(binding, action)
    }

    private fun <T> withBindingDataMutationLock(binding: BotPluginBinding, action: () -> T): T =
        withBindingDataLock(binding) {
            withRuntimeMutationFence(binding.id, action)
        }

    private fun <T> withRuntimeMutationFence(bindingId: UUID, action: () -> T): T {
        try {
            runtime.beforeBindingMutation(bindingId)
        } catch (exception: RuntimeException) {
            runCatching { runtime.bindingMutationAborted(bindingId) }.exceptionOrNull()?.let(exception::addSuppressed)
            throw failure(
                HttpStatus.CONFLICT,
                "PLUGIN_BINDING_BUSY",
                "Plugin binding is still processing work and cannot be changed",
            )
        }

        return try {
            val result = action()
            runtime.bindingMutationCompleted(bindingId)
            result
        } catch (exception: Throwable) {
            runCatching { runtime.bindingMutationAborted(bindingId) }.exceptionOrNull()?.let(exception::addSuppressed)
            throw exception
        }
    }

    private fun <T> withBindingDataLock(binding: BotPluginBinding, action: () -> T): T =
        withBotLock(binding.botId) {
            val slot = "${binding.botId}\u0000${binding.pluginId}"
            val slotHash = sha256(slot.toByteArray(StandardCharsets.UTF_8))
            withInternalLock("slot-$slotHash.lock", action)
        }

    private fun <T> withBotLock(botId: BotId, action: () -> T): T =
        withInternalLock("bot-${botId}.lock", action)

    private fun <T> withInternalLock(lockName: String, action: () -> T): T {
        val dataRoot = host.pluginDataRoot.toAbsolutePath().normalize()
        val lockDirectory = dataRoot.resolve(INTERNAL_LOCK_DIRECTORY)
        val lockFile = lockDirectory.resolve(lockName)
        val localLock = LOCAL_LOCKS.computeIfAbsent(lockFile) { ReentrantLock() }
        return localLock.withLock {
            try {
                Files.createDirectories(dataRoot)
                if (!Files.isDirectory(dataRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dataRoot)) {
                    throw failure(
                        HttpStatus.CONFLICT,
                        "PLUGIN_DATA_DIRECTORY_INVALID",
                        "Configured plugin data root is not a direct directory",
                    )
                }
                if (Files.exists(lockDirectory, LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isDirectory(lockDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockDirectory))
                ) {
                    throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_LOCK_INVALID", "Plugin data lock directory is invalid")
                }
                Files.createDirectories(lockDirectory)
                val expectedLockDirectory = dataRoot.toRealPath().resolve(INTERNAL_LOCK_DIRECTORY).normalize()
                if (lockDirectory.toRealPath() != expectedLockDirectory || Files.isSymbolicLink(lockFile)) {
                    throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_LOCK_INVALID", "Plugin data lock path is invalid")
                }
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use { action() }
                }
            } catch (exception: PluginAdministrationException) {
                throw exception
            } catch (exception: IOException) {
                throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_LOCK_FAILED", "Unable to lock plugin data directory")
            }
        }
    }

    private fun binding(id: UUID): BotPluginBinding = bindings.findById(id)
        ?: throw failure(HttpStatus.NOT_FOUND, "BINDING_NOT_FOUND", "Plugin binding does not exist")

    private fun bindingOwnedBy(expected: BotPluginBinding): Boolean {
        val current = bindings.findById(expected.id) ?: return false
        return current.pluginId == expected.pluginId && current.botId == expected.botId
    }

    private fun requireBindingOwnership(expected: BotPluginBinding) {
        if (!bindingOwnedBy(expected)) {
            throw failure(
                HttpStatus.CONFLICT,
                "BINDING_CREATION_ABORTED",
                "Plugin binding disappeared before its data directory was initialized",
            )
        }
    }

    private fun requireRoot(binding: BotPluginBinding): Path = validatedRoot(binding, createIfMissing = true)

    private fun requireExistingRoot(binding: BotPluginBinding): Path {
        val root = validatedRoot(binding, createIfMissing = false)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(HttpStatus.NOT_FOUND, "PLUGIN_DIRECTORY_NOT_FOUND", "Plugin data directory does not exist")
        }
        return root
    }

    private fun validatedRoot(binding: BotPluginBinding, createIfMissing: Boolean): Path {
        val dataRoot = host.pluginDataRoot.toAbsolutePath().normalize()
        val root = host.bindingDataDirectory(binding).toAbsolutePath().normalize()
        if (root == dataRoot || !root.startsWith(dataRoot) || dataRoot.relativize(root).nameCount != 2) {
            throw failure(
                HttpStatus.CONFLICT,
                "PLUGIN_DATA_DIRECTORY_INVALID",
                "Plugin data directory must stay inside the configured data root",
            )
        }
        try {
            if (createIfMissing) Files.createDirectories(dataRoot)
            if (!Files.exists(dataRoot, LinkOption.NOFOLLOW_LINKS)) return root
            if (!Files.isDirectory(dataRoot)) {
                throw failure(
                    HttpStatus.CONFLICT,
                    "PLUGIN_DATA_DIRECTORY_INVALID",
                    "Configured plugin data root is not a directory",
                )
            }
            val realDataRoot = dataRoot.toRealPath()
            requireDirectDescendants(dataRoot, root, realDataRoot)
            if (createIfMissing) {
                Files.createDirectories(root)
                requireDirectDescendants(dataRoot, root, realDataRoot)
            }
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(
                    HttpStatus.CONFLICT,
                    "PLUGIN_DATA_DIRECTORY_INVALID",
                    "Plugin data directory is not a directory",
                )
            }
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_DIRECTORY_UNAVAILABLE", "Plugin data directory is unavailable")
        }
        return root
    }

    @Throws(IOException::class)
    private fun requireDirectDescendants(dataRoot: Path, root: Path, realDataRoot: Path) {
        var current = dataRoot
        for (part in dataRoot.relativize(root)) {
            current = current.resolve(part)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(current)) {
                throw failure(
                    HttpStatus.CONFLICT,
                    "PLUGIN_DATA_DIRECTORY_INVALID",
                    "Plugin data directory cannot contain symbolic-link ancestors",
                )
            }
            val expectedRealPath = realDataRoot.resolve(dataRoot.relativize(current)).normalize()
            if (current.toRealPath() != expectedRealPath) {
                throw failure(
                    HttpStatus.CONFLICT,
                    "PLUGIN_DATA_DIRECTORY_INVALID",
                    "Plugin data directory cannot leave its direct path under the configured data root",
                )
            }
        }
    }

    private fun resolve(root: Path, value: String, allowEmpty: Boolean, allowFinalSymbolicLink: Boolean): Path {
        if (value.any { Character.isISOControl(it) }) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_PATH_INVALID", "Plugin file path is invalid")
        }
        val normalizedValue = value.trim().replace('\\', '/')
        if (normalizedValue.isEmpty()) {
            if (allowEmpty) return root
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_PATH_REQUIRED", "Plugin file path is required")
        }
        val relative = try { Path.of(normalizedValue).normalize() } catch (exception: RuntimeException) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_PATH_INVALID", "Plugin file path is invalid")
        }
        if (relative.isAbsolute || relative.startsWith("..")) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_PATH_INVALID", "Plugin file path must stay inside its binding directory")
        }
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || target == root && !allowEmpty) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_PATH_INVALID", "Plugin file path must stay inside its binding directory")
        }
        var current = root
        val parts = root.relativize(target).toList()
        parts.forEachIndexed { index, part ->
            current = current.resolve(part)
            val final = index == parts.lastIndex
            if (Files.isSymbolicLink(current) && !(final && allowFinalSymbolicLink)) {
                throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_SYMLINK_REJECTED", "Symbolic links cannot be opened through the file manager")
            }
        }
        return target
    }

    private fun entry(root: Path, path: Path): PluginFileEntryResponse {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_FILE_READ_FAILED", "Unable to read plugin file")
        }
        return PluginFileEntryResponse(
            path.fileName.toString(),
            relative(root, path),
            attributes.isDirectory,
            if (attributes.isDirectory) 0L else attributes.size(),
            attributes.lastModifiedTime().toInstant(),
            if (attributes.isDirectory) null else contentType(path),
        )
    }

    private fun relative(root: Path, path: Path): String = root.relativize(path).joinToString("/") { it.toString() }

    private fun contentType(path: Path): String? = runCatching { Files.probeContentType(path) }.getOrNull()

    private fun validateJsonFile(path: Path, content: String) {
        if (!path.fileName.toString().lowercase(Locale.ROOT).endsWith(".json")) return
        try {
            if (mapper.readTree(content) == null) {
                throw failure(HttpStatus.BAD_REQUEST, "INVALID_JSON_FILE", "JSON file content is invalid")
            }
        } catch (exception: JsonProcessingException) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_JSON_FILE", "JSON file content is invalid")
        }
    }

    private fun validateSavedContentSize(configuration: Boolean, sizeBytes: Int) {
        val maximum = if (configuration) MAX_CONFIGURATION_BYTES else MAX_TEXT_BYTES
        if (sizeBytes.toLong() <= maximum) return
        if (configuration) {
            throw failure(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PLUGIN_CONFIG_TOO_LARGE",
                "Plugin configuration cannot exceed 64 KiB",
            )
        }
        throw failure(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "PLUGIN_FILE_CONTENT_TOO_LARGE",
            "Plugin text content cannot exceed 2 MiB",
        )
    }

    private fun safeUploadName(value: String?): String {
        val name = value?.trim().orEmpty()
        if (name.isEmpty() || name == "." || name == ".." || name.any { Character.isISOControl(it) }) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "Plugin file name is invalid")
        }
        val path = try { Path.of(name) } catch (exception: RuntimeException) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "Plugin file name is invalid")
        }
        if (path.isAbsolute || path.nameCount != 1 || path.fileName.toString() != name) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "Plugin file name is invalid")
        }
        return name
    }

    private fun normalizedUploadHash(overwrite: Boolean, value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!overwrite) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_OVERWRITE_REQUIRED", "Expected hash requires overwrite confirmation")
        }
        if (!SHA_256_PATTERN.matches(normalized)) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_HASH_INVALID", "Expected SHA-256 is invalid")
        }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun decodeUtf8(bytes: ByteArray, status: HttpStatus, code: String, message: String): String {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            throw failure(status, code, message)
        }
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            moveAtomic(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeAtomicNew(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (exception: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyAtomic(source: Path, target: Path) {
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveAtomic(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyAtomicNew(source: Path, target: Path) {
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (exception: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveAtomic(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Throws(IOException::class)
    private fun deleteTree(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun removeEmptyParents(start: Path?, stopExclusive: Path?) {
        var current = start
        while (current != null && current != stopExclusive) {
            try {
                Files.delete(current)
            } catch (_: IOException) {
                return
            }
            current = current.parent
        }
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

    @Throws(IOException::class)
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun failure(status: HttpStatus, code: String, message: String) =
        PluginAdministrationException(status, code, message)
}
