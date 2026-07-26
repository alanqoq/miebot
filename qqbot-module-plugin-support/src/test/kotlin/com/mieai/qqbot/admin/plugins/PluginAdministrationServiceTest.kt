package com.mieai.qqbot.admin.plugins

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

class PluginAdministrationServiceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `scans manifest and hash without loading plugin code`() {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Plugin-Id", "support")
            mainAttributes.putValue("Plugin-Name", "Support Plugin")
            mainAttributes.putValue("Plugin-Version", "1.2.3")
            mainAttributes.putValue("Plugin-Api-Version", "1")
            mainAttributes.putValue("Plugin-Class", "example.SupportPlugin")
            mainAttributes.putValue("Plugin-Config-Schema", "plugin-schema.json")
            mainAttributes.putValue("Plugin-Default-Config", "plugin-default.json")
        }
        writeManifestJar(directory.resolve("support.jar"), manifest)

        val response = PluginAdministrationService(directory.toString()).scan(null)

        assertThat(response.directoryExists).isTrue()
        assertThat(response.runtimeAvailable).isFalse()
        val artifact = response.items.single()
        assertThat(artifact.id).isEqualTo("support")
        assertThat(artifact.name).isEqualTo("Support Plugin")
        assertThat(artifact.version).isEqualTo("1.2.3")
        assertThat(artifact.status).isEqualTo("DISCOVERED")
        assertThat(artifact.loaded).isFalse()
        assertThat(artifact.sha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `rejects oversized or control character query before reading directory`() {
        val service = PluginAdministrationService(directory.toString())

        assertThatThrownBy { service.scan("x".repeat(129)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.scan("bad\nquery") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reports missing directory without creating it`() {
        val missing = directory.resolve("missing")

        val response = PluginAdministrationService(missing.toString()).scan(null)

        assertThat(response.directoryExists).isFalse()
        assertThat(response.items).isEmpty()
        assertThat(Files.exists(missing)).isFalse()
    }

    private fun writeManifestJar(target: Path, manifest: Manifest) {
        JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
            output.putNextEntry(ZipEntry("plugin-schema.json"))
            output.write("""{"type":"object"}""".toByteArray(StandardCharsets.UTF_8))
            output.closeEntry()
            output.putNextEntry(ZipEntry("plugin-default.json"))
            output.write("{}".toByteArray(StandardCharsets.UTF_8))
            output.closeEntry()
            output.finish()
        }
    }
}
