package com.mieai.qqbot.module.host

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.mieai.qqbot.module.api.ModuleBotSettingsContribution
import com.mieai.qqbot.module.api.ModuleDependency
import com.mieai.qqbot.module.api.ModuleDescriptor
import com.mieai.qqbot.module.api.ModuleWebContribution
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.Locale
import java.util.jar.JarFile

internal class ModuleArtifactScanner {
    private val objectMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun scan(directory: Path): ModuleArtifactRegistry {
        val resolved = directory.toAbsolutePath().normalize()
        check(Files.isDirectory(resolved)) { "Framework module directory does not exist: $resolved" }
        val jars = try {
            Files.list(resolved).use { stream ->
                stream.filter(Files::isRegularFile)
                    .filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".jar") }
                    .sorted(compareBy { it.fileName.toString() })
                    .toList()
            }
        } catch (error: IOException) {
            throw IllegalStateException("Unable to scan framework module directory $resolved", error)
        }
        check(jars.isNotEmpty()) { "No framework module JARs found in $resolved" }

        val artifacts = linkedMapOf<String, ModuleArtifact>()
        jars.forEach { jar ->
            val artifact = read(jar)
            val duplicate = artifacts.putIfAbsent(artifact.descriptor.id, artifact)
            check(duplicate == null) {
                "Duplicate framework module ${artifact.descriptor.id} in ${duplicate!!.path.fileName} and ${artifact.path.fileName}"
            }
        }
        validateGlobalWebContributions(artifacts.values)
        val descriptors = artifacts.mapValues { it.value.descriptor }
        return ModuleArtifactRegistry(resolved, artifacts, ModuleGraph.resolve(descriptors))
    }

    private fun read(path: Path): ModuleArtifact {
        try {
            JarFile(path.toFile(), true).use { jar ->
                val descriptors = jar.stream().filter { DESCRIPTOR_PATH == it.name }.toList()
                check(descriptors.size == 1) { "Module JAR ${path.fileName} must contain exactly one $DESCRIPTOR_PATH" }
                val descriptorEntry = descriptors.first()
                check(descriptorEntry.size <= MAX_DESCRIPTOR_BYTES) { "Module descriptor is too large in ${path.fileName}" }
                val document = jar.getInputStream(descriptorEntry).use { input ->
                    val bytes = input.readNBytes((MAX_DESCRIPTOR_BYTES + 1).toInt())
                    check(bytes.size <= MAX_DESCRIPTOR_BYTES) { "Module descriptor is too large in ${path.fileName}" }
                    objectMapper.readValue(bytes, DescriptorDocument::class.java)
                }
                val descriptor = toDescriptor(document)
                validateOwnedResources(jar, path, descriptor)
                validateWebAssets(jar, path, descriptor)
                return ModuleArtifact.from(path, descriptor, sha256(path))
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: IOException) {
            throw IllegalStateException("Unable to read framework module JAR $path", error)
        } catch (error: RuntimeException) {
            throw IllegalStateException("Unable to read framework module JAR $path", error)
        }
    }

    private fun toDescriptor(document: DescriptorDocument?): ModuleDescriptor {
        if (document == null || document.schemaVersion != 1) {
            throw IllegalArgumentException("schemaVersion must be 1")
        }
        val dependencyDocuments = document.dependencies ?: emptyList()
        val capabilities = document.capabilities ?: emptyList()
        val uniqueCapabilities = capabilities.toCollection(linkedSetOf())
        require(uniqueCapabilities.size == capabilities.size) { "capabilities contain duplicates" }
        val pages = document.web?.pages ?: emptyList()
        val botSettings = document.web?.botSettings ?: emptyList()
        val dependencies = dependencyDocuments.map { ModuleDependency(it.moduleId!!, it.minimumVersion!!, it.optional == true) }
        val contributions = pages.map {
            ModuleWebContribution.webComponent(it.id!!, it.label!!, it.route!!, it.icon!!, it.order, it.entrypoint!!, it.customElement!!)
        }
        val settingsContributions = botSettings.map {
            ModuleBotSettingsContribution(it.id!!, it.label!!, it.order, it.entrypoint!!, it.customElement!!)
        }
        return ModuleDescriptor.create(
            document.id!!,
            document.name!!,
            document.version!!,
            document.minimumFrameworkVersion!!,
            dependencies,
            uniqueCapabilities,
            contributions,
            settingsContributions,
        )
    }

    private fun validateOwnedResources(jar: JarFile, path: Path, descriptor: ModuleDescriptor) {
        val ownPrefix = "$MODULE_RESOURCE_ROOT${descriptor.id}/"
        val foreignResource = jar.stream()
            .filter { !it.isDirectory }
            .filter { it.name.startsWith(MODULE_RESOURCE_ROOT) }
            .filter { !it.name.startsWith(ownPrefix) }
            .findFirst()
            .orElse(null)
        if (foreignResource != null) {
            throw IllegalStateException(
                "Module JAR ${path.fileName} contains resources owned by another module: ${foreignResource.name}",
            )
        }
    }

    private fun validateWebAssets(jar: JarFile, path: Path, descriptor: ModuleDescriptor) {
        descriptor.webContributions.forEach { contribution ->
            val resource = "$MODULE_RESOURCE_ROOT${descriptor.id}/web/${contribution.assetPath}"
            val entry = jar.getJarEntry(resource)
            check(entry != null && !entry.isDirectory) { "Module JAR ${path.fileName} is missing Web entrypoint $resource" }
        }
        descriptor.botSettingsContributions.forEach { contribution ->
            val resource = "$MODULE_RESOURCE_ROOT${descriptor.id}/web/${contribution.assetPath}"
            val entry = jar.getJarEntry(resource)
            check(entry != null && !entry.isDirectory) { "Module JAR ${path.fileName} is missing Web entrypoint $resource" }
        }
    }

    private fun validateGlobalWebContributions(artifacts: Iterable<ModuleArtifact>) {
        val routes = linkedSetOf<String>()
        val elements = linkedSetOf<String?>()
        artifacts.forEach { artifact ->
            artifact.descriptor.webContributions.forEach { contribution ->
                check(routes.add(contribution.route)) { "Duplicate module Web route: ${contribution.route}" }
                check(elements.add(contribution.customElement)) { "Duplicate module custom element: ${contribution.customElement}" }
            }
            artifact.descriptor.botSettingsContributions.forEach { contribution ->
                check(elements.add(contribution.customElement)) { "Duplicate module custom element: ${contribution.customElement}" }
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (error: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", error)
        }
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private class DescriptorDocument {
        var schemaVersion: Int? = null
        var id: String? = null
        var name: String? = null
        var version: String? = null
        var minimumFrameworkVersion: String? = null
        var dependencies: List<DependencyDocument>? = null
        var capabilities: List<String>? = null
        var web: WebDocument? = null
    }

    private class DependencyDocument {
        var moduleId: String? = null
        var minimumVersion: String? = null
        var optional: Boolean? = null
    }

    private class WebDocument {
        var pages: List<PageDocument>? = null
        var botSettings: List<BotSettingsDocument>? = null
    }

    private class PageDocument {
        var id: String? = null
        var label: String? = null
        var route: String? = null
        var icon: String? = null
        var order: Int = 0
        var entrypoint: String? = null
        var customElement: String? = null
    }

    private class BotSettingsDocument {
        var id: String? = null
        var label: String? = null
        var order: Int = 0
        var entrypoint: String? = null
        var customElement: String? = null
    }

    companion object {
        const val DESCRIPTOR_PATH = "META-INF/qqbot/module.json"
        private const val MODULE_RESOURCE_ROOT = "META-INF/qqbot/modules/"
        private const val MAX_DESCRIPTOR_BYTES = 256 * 1024L
    }
}
