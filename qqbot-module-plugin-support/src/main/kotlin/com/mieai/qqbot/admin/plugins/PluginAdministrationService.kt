package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginConfigurationCodec
import com.mieai.qqbot.plugin.host.PluginConfigurationDescriptor
import com.mieai.qqbot.plugin.host.PluginConfigurationFormat
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Instant
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.jar.JarFile
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/** Scans the trusted, operator-mounted plugin directory without executing plugin code. */
@Service
class PluginAdministrationService(
    @Value("\${qqbot.plugins.directory:/plugins}") pluginDirectory: String,
    host: ObjectProvider<Pf4jPluginHost>? = null,
    bindings: ObjectProvider<BotPluginBindingRepository>? = null,
    runtime: ObjectProvider<PluginRuntimeService>? = null,
    files: ObjectProvider<PluginBindingFileService>? = null,
) {
    private val pluginDirectory: Path = normalizePluginDirectory(pluginDirectory)
    private val host = host?.ifAvailable
    private val bindings = bindings?.ifAvailable
    private val runtime = runtime?.ifAvailable
    private val files = files?.ifAvailable
    private val configurationCodec = PluginConfigurationCodec()

    fun scan(query: String?): PluginInventoryResponse {
        val scannedAt = Instant.now()
        val normalizedQuery = normalizeQuery(query)
        if (Files.notExists(pluginDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return PluginInventoryResponse(
                emptyList(),
                pluginDirectory.toString(),
                false,
                host?.isStarted() == true,
                null,
                scannedAt,
            )
        }
        if (!Files.isDirectory(pluginDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return PluginInventoryResponse(
                emptyList(),
                pluginDirectory.toString(),
                false,
                host?.isStarted() == true,
                "插件路径不是目录",
                scannedAt,
            )
        }

        val artifacts = mutableListOf<PluginArtifactResponse>()
        val bindingCounts = mutableMapOf<String, Int>()
        val enabledBindingCounts = mutableMapOf<String, Int>()
        bindings?.findAll()?.forEach { binding ->
            bindingCounts.merge(binding.pluginId, 1) { first, second -> first + second }
            if (binding.enabled) {
                enabledBindingCounts.merge(binding.pluginId, 1) { first, second -> first + second }
            }
        }

        var scanError: String? = null
        try {
            Files.list(pluginDirectory).use { paths ->
                val jars = paths
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".jar") }
                    .sorted(compareBy { it.fileName.toString() })
                    .limit(MAX_ARTIFACTS.toLong())
                    .toList()
                for (jar in jars) {
                    val artifact = decorate(inspect(jar), bindingCounts, enabledBindingCounts)
                    if (normalizedQuery == null || matches(artifact, normalizedQuery)) {
                        artifacts += artifact
                    }
                }
            }
        } catch (exception: IOException) {
            scanError = "无法读取插件目录：${safeMessage(exception)}"
        }
        return PluginInventoryResponse(
            artifacts,
            pluginDirectory.toString(),
            true,
            host?.isStarted() == true,
            scanError,
            scannedAt,
        )
    }

    fun reload() {
        val activeRuntime = runtime ?: error("Plugin runtime is not available")
        activeRuntime.reloadPlugins()
        files?.initializeMissingBindings()
    }

    fun upload(file: MultipartFile?): PluginUploadResponse {
        val activeRuntime = runtime
        if (activeRuntime == null || host?.isStarted() != true) {
            throw failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PLUGIN_RUNTIME_UNAVAILABLE",
                "Plugin runtime is not available",
            )
        }
        if (file == null || file.isEmpty) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_REQUIRED", "请选择要上传的插件 JAR")
        }
        val originalName = safeUploadName(file.originalFilename)
        if (!originalName.lowercase(Locale.ROOT).endsWith(".jar")) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_TYPE_INVALID", "只允许上传 .jar 插件制品")
        }
        if (file.size !in 1..MAX_UPLOAD_BYTES) {
            throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_FILE_TOO_LARGE", "插件 JAR 不能超过 64 MiB")
        }

        val stagingDirectory = pluginDirectory.resolve(".staging").normalize()
        val staged = stagingDirectory.resolve("${UUID.randomUUID()}.jar").normalize()
        if (staged.parent != stagingDirectory) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "插件文件名无效")
        }
        try {
            Files.createDirectories(stagingDirectory)
            BufferedInputStream(file.inputStream).use { input ->
                Files.newOutputStream(
                    staged,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        written += count
                        if (written > MAX_UPLOAD_BYTES) {
                            throw failure(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "PLUGIN_FILE_TOO_LARGE",
                                "插件 JAR 不能超过 64 MiB",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val result = activeRuntime.installArtifact(staged)
            files?.initializeMissingBindings()
            val artifact = scan(null).items.firstOrNull {
                it.id == result.artifact.id && it.sha256 == result.artifact.sha256
            } ?: error("Installed plugin is missing from inventory")
            return PluginUploadResponse.from(result, artifact)
        } catch (exception: PluginAdministrationException) {
            throw exception
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_UPLOAD_IO_FAILED", "无法保存上传的插件 JAR")
        } catch (exception: RuntimeException) {
            throw failure(
                HttpStatus.BAD_REQUEST,
                "PLUGIN_ARTIFACT_INVALID",
                "插件校验或热加载失败：${safeMessage(exception)}",
            )
        } finally {
            try {
                Files.deleteIfExists(staged)
            } catch (_: IOException) {
                // Best-effort cleanup of the temporary upload.
            }
        }
    }

    private fun decorate(
        artifact: PluginArtifactResponse,
        bindingCounts: Map<String, Int>,
        enabledBindingCounts: Map<String, Int>,
    ): PluginArtifactResponse {
        val loaded = host?.isLoaded(artifact.id, artifact.sha256) == true
        return PluginArtifactResponse(
            artifact.id,
            artifact.name,
            artifact.version,
            artifact.apiCompatibility,
            artifact.fileName,
            artifact.sizeBytes,
            artifact.modifiedAt,
            artifact.sha256,
            if (loaded) "LOADED" else artifact.status,
            if (loaded) null else artifact.error,
            artifact.defaultConfigJson,
            loaded,
            bindingCounts.getOrDefault(artifact.id, 0),
            enabledBindingCounts.getOrDefault(artifact.id, 0),
            artifact.defaultConfigContent,
            artifact.configFormat,
            artifact.configFileName,
        )
    }

    private fun inspect(jar: Path): PluginArtifactResponse {
        val fileName = jar.fileName.toString()
        val size: Long
        val modifiedAt: Instant
        try {
            size = Files.size(jar)
            modifiedAt = Files.getLastModifiedTime(jar, LinkOption.NOFOLLOW_LINKS).toInstant()
        } catch (_: IOException) {
            return invalid(fileName, "无法读取文件属性")
        }

        val hash = try {
            sha256(jar, size)
        } catch (_: IOException) {
            return invalid(fileName, "无法计算 SHA-256")
        }

        try {
            JarFile(jar.toFile(), false).use { jarFile ->
                val manifest = jarFile.manifest ?: return PluginArtifactResponse(
                    fileName,
                    fileName,
                    "",
                    "",
                    fileName,
                    size,
                    modifiedAt,
                    hash,
                    STATUS_INVALID,
                    "JAR 缺少 MANIFEST.MF",
                    null,
                    false,
                    0,
                    0,
                )
                val attributes = manifest.mainAttributes
                val id = requireNotNull(
                    firstNonBlank(
                        attributes.getValue("Plugin-Id"),
                        attributes.getValue("Implementation-Id"),
                        stripJarExtension(fileName),
                    ),
                )
                val name = requireNotNull(
                    firstNonBlank(
                        attributes.getValue("Plugin-Name"),
                        attributes.getValue("Implementation-Title"),
                        id,
                    ),
                )
                val version = requireNotNull(
                    firstNonBlank(
                        attributes.getValue("Plugin-Version"),
                        attributes.getValue("Implementation-Version"),
                        "",
                    ),
                )
                val apiCompatibility = requireNotNull(
                    firstNonBlank(
                        attributes.getValue("Plugin-Api-Version"),
                        attributes.getValue("Plugin-Requires"),
                        "",
                    ),
                )
                val entrypoint = firstNonBlank(
                    attributes.getValue("Plugin-Class"),
                    attributes.getValue("Plugin-Entry"),
                    null,
                )
                val configurationSchema = attributes.getValue("Plugin-Config-Schema")
                val defaultConfiguration = attributes.getValue("Plugin-Default-Config")
                var supported = entrypoint != null &&
                    !configurationSchema.isNullOrBlank() &&
                    !defaultConfiguration.isNullOrBlank()
                var status = if (supported) STATUS_DISCOVERED else STATUS_UNSUPPORTED
                var error = when {
                    entrypoint == null -> "Manifest 未声明插件入口"
                    configurationSchema.isNullOrBlank() -> "Manifest 未声明配置 Schema"
                    defaultConfiguration.isNullOrBlank() -> "Manifest 未声明默认配置"
                    else -> null
                }
                var defaultConfigJson: String? = null
                var defaultConfigContent: String? = null
                var configFormat: String? = null
                var configFileName: String? = null
                val configurationDescriptor = if (supported) {
                    try {
                        PluginConfigurationDescriptor.fromDefaultResource(requireNotNull(defaultConfiguration))
                    } catch (_: IllegalArgumentException) {
                        supported = false
                        status = STATUS_UNSUPPORTED
                        error = "插件默认配置必须使用 .json、.yml 或 .yaml 扩展名"
                        null
                    }
                } else {
                    null
                }
                if (supported && jarFile.getJarEntry(configurationSchema) == null) {
                    supported = false
                    status = STATUS_UNSUPPORTED
                    error = "JAR 缺少配置 Schema 资源"
                }
                val defaultEntry = if (supported) jarFile.getJarEntry(defaultConfiguration) else null
                if (supported && defaultEntry == null) {
                    supported = false
                    status = STATUS_UNSUPPORTED
                    error = "JAR 缺少默认配置资源"
                }
                if (supported) {
                    try {
                        jarFile.getInputStream(defaultEntry).use { input ->
                            val content = configurationCodec.decodeUtf8(input.readAllBytes())
                            val parsed = configurationCodec.parse(
                                content,
                                requireNotNull(configurationDescriptor).format,
                            )
                            if (!parsed.isObject) {
                                supported = false
                                status = STATUS_UNSUPPORTED
                                error = "插件默认配置必须是对象"
                            } else {
                                defaultConfigContent = content
                                configFormat = configurationDescriptor.format.name
                                configFileName = configurationDescriptor.fileName
                                if (configurationDescriptor.format == PluginConfigurationFormat.JSON) {
                                    defaultConfigJson = content
                                }
                            }
                        }
                    } catch (_: Exception) {
                        supported = false
                        status = STATUS_UNSUPPORTED
                        error = "无法读取插件默认配置"
                    }
                }
                return PluginArtifactResponse(
                    id,
                    name,
                    version,
                    apiCompatibility,
                    fileName,
                    size,
                    modifiedAt,
                    hash,
                    status,
                    error,
                    defaultConfigJson,
                    false,
                    0,
                    0,
                    defaultConfigContent,
                    configFormat,
                    configFileName,
                )
            }
        } catch (_: IOException) {
            return invalid(fileName, "无法读取 JAR manifest")
        } catch (_: SecurityException) {
            return invalid(fileName, "无法读取 JAR manifest")
        }
    }

    private companion object {
        const val MAX_ARTIFACTS = 500
        const val MAX_HASH_BYTES = 512L * 1024L * 1024L
        const val MAX_UPLOAD_BYTES = 64L * 1024L * 1024L
        const val STATUS_DISCOVERED = "DISCOVERED"
        const val STATUS_INVALID = "INVALID"
        const val STATUS_UNSUPPORTED = "UNSUPPORTED"

        fun normalizePluginDirectory(value: String): Path {
            require(value.isNotBlank()) { "pluginDirectory must not be blank" }
            return Path.of(value).toAbsolutePath().normalize()
        }

        @Throws(IOException::class)
        fun sha256(jar: Path, size: Long): String {
            if (size > MAX_HASH_BYTES) throw IOException("文件超过 512 MiB 扫描上限")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = 0L
                BufferedInputStream(Files.newInputStream(jar, StandardOpenOption.READ)).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        read += count
                        if (read > MAX_HASH_BYTES) throw IOException("文件超过 512 MiB 扫描上限")
                        digest.update(buffer, 0, count)
                    }
                }
                return HexFormat.of().formatHex(digest.digest())
            } catch (exception: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", exception)
            }
        }

        fun invalid(fileName: String, error: String): PluginArtifactResponse = PluginArtifactResponse(
            fileName,
            fileName,
            "",
            "",
            fileName,
            0,
            Instant.EPOCH,
            "",
            STATUS_INVALID,
            error,
            null,
            false,
            0,
            0,
        )

        fun matches(artifact: PluginArtifactResponse, query: String): Boolean = listOfNotNull(
            artifact.id,
            artifact.name,
            artifact.version,
            artifact.fileName,
            artifact.status,
        ).any { it.lowercase(Locale.ROOT).contains(query) }

        fun normalizeQuery(query: String?): String? {
            if (query.isNullOrBlank()) return null
            val value = query.trim().lowercase(Locale.ROOT)
            require(value.length <= 128 && value.codePoints().noneMatch(Character::isISOControl)) {
                "query is invalid"
            }
            return value
        }

        fun firstNonBlank(first: String?, second: String?, fallback: String?): String? = when {
            !first.isNullOrBlank() -> first.trim()
            !second.isNullOrBlank() -> second.trim()
            else -> fallback
        }

        fun stripJarExtension(fileName: String): String =
            if (fileName.lowercase(Locale.ROOT).endsWith(".jar")) fileName.dropLast(4) else fileName

        fun safeMessage(exception: Exception): String {
            val message = exception.message
            if (message.isNullOrBlank()) return exception.javaClass.simpleName
            return message.take(256)
        }

        fun safeUploadName(value: String?): String {
            if (value.isNullOrBlank()) return "plugin.jar"
            val name = try {
                Path.of(value).fileName.toString()
            } catch (_: RuntimeException) {
                throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "插件文件名无效")
            }
            if (name.length > 255 || name.codePoints().anyMatch(Character::isISOControl)) {
                throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "插件文件名无效")
            }
            return name
        }

        fun failure(status: HttpStatus, code: String, message: String): PluginAdministrationException =
            PluginAdministrationException(status, code, message)
    }
}
