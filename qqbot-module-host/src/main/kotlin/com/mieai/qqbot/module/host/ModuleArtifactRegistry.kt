package com.mieai.qqbot.module.host

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarFile
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.Resource

/** Validated module artifacts in dependency order. */
class ModuleArtifactRegistry internal constructor(
    private val directoryValue: Path,
    artifacts: Map<String, ModuleArtifact>,
    startOrder: List<String>,
) : AutoCloseable {
    private val artifactsValue = artifacts.toMap()
    private val startOrderValue = startOrder.toList()
    private val artifactClassLoaders = ConcurrentHashMap<String, URLClassLoader>()
    private val openedJars = ConcurrentHashMap<String, JarFile>()

    fun directory(): Path = directoryValue

    fun artifacts(): List<ModuleArtifact> = artifactsValue.values.sortedBy { it.descriptor.id }

    fun startOrder(): List<ModuleArtifact> = startOrderValue.map { artifactsValue.getValue(it) }

    fun find(moduleId: String): ModuleArtifact? = artifactsValue[moduleId]

    fun require(moduleId: String): ModuleArtifact = find(moduleId)
        ?: throw IllegalArgumentException("Unknown framework module: $moduleId")

    fun findWebAsset(moduleId: String, assetPath: String?): Resource? {
        val artifact = artifactsValue[moduleId] ?: return null
        val path = normalizeAssetPath(assetPath)
        val resourceName = "META-INF/qqbot/modules/$moduleId/web/$path"
        val jar = openJar(artifact)
        val entry = jar.getJarEntry(resourceName) ?: return null
        if (entry.isDirectory) return null
        return JarEntryResource(jar, entry, artifact.path.fileName.toString())
    }

    fun artifactClassLoader(moduleId: String): ClassLoader {
        val artifact = require(moduleId)
        return artifactClassLoaders.computeIfAbsent(moduleId) {
            try {
                URLClassLoader(arrayOf(artifact.path.toUri().toURL()), ModuleArtifactRegistry::class.java.classLoader)
            } catch (error: IOException) {
                throw IllegalStateException("Unable to create class loader for $moduleId", error)
            }
        }
    }

    fun containsResourcePrefix(moduleId: String, resourcePrefix: String?): Boolean {
        val artifact = require(moduleId)
        val expectedPrefix = "META-INF/qqbot/modules/$moduleId/"
        require(
            resourcePrefix != null &&
                resourcePrefix.startsWith(expectedPrefix) &&
                !resourcePrefix.contains("..") &&
                !resourcePrefix.contains("\\"),
        ) {
            "Module resource prefix is invalid"
        }
        return try {
            openJar(artifact).stream().anyMatch { !it.isDirectory && it.name.startsWith(resourcePrefix) }
        } catch (error: RuntimeException) {
            throw IllegalStateException("Unable to inspect resources for $moduleId", error)
        }
    }

    override fun close() {
        val failures = mutableListOf<IOException>()
        artifactClassLoaders.values.forEach { loader ->
            try { loader.close() } catch (error: IOException) { failures += error }
        }
        artifactClassLoaders.clear()
        openedJars.values.forEach { jar ->
            try { jar.close() } catch (error: IOException) { failures += error }
        }
        openedJars.clear()
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Unable to close module artifact class loaders", failures.first())
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun openJar(artifact: ModuleArtifact): JarFile = openedJars.computeIfAbsent(artifact.descriptor.id) {
        try { JarFile(artifact.path.toFile(), true) }
        catch (error: IOException) {
            throw IllegalStateException("Unable to open framework module JAR ${artifact.path}", error)
        }
    }

    private class JarEntryResource(
        private val jar: JarFile,
        private val entry: JarEntry,
        private val artifactName: String,
    ) : AbstractResource() {
        override fun getDescription(): String = "$artifactName!/${entry.name}"
        override fun getFilename(): String = entry.name.substringAfterLast('/')
        override fun contentLength(): Long = entry.size
        override fun getInputStream(): InputStream = jar.getInputStream(entry)
    }

    companion object {
        init { URLConnection.setDefaultUseCaches("jar", false) }
        fun load(directory: Path): ModuleArtifactRegistry = ModuleArtifactScanner().scan(directory)

        private fun normalizeAssetPath(value: String?): String {
            require(value != null) { "assetPath must not be null" }
            val path = if (value.startsWith('/')) value.substring(1) else value
            require(path.isNotBlank() && !path.contains("..") && !path.contains("\\") && !path.contains('\u0000')) {
                "assetPath is invalid"
            }
            return path
        }
    }
}
