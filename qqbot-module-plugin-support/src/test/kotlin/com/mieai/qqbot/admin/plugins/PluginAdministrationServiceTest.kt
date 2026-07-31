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
        assertThat(artifact.defaultConfigContent).isEqualTo("{}")
        assertThat(artifact.defaultConfigJson).isEqualTo("{}")
        assertThat(artifact.configFormat).isEqualTo("JSON")
        assertThat(artifact.configFileName).isEqualTo("config.json")
    }

    @Test
    fun `scans yaml defaults without normalizing their content`() {
        val defaultConfiguration = """# visible to the plugin
            |enabled: true
            |message: hello
            |
        """.trimMargin()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Plugin-Id", "yaml-support")
            mainAttributes.putValue("Plugin-Name", "YAML Support Plugin")
            mainAttributes.putValue("Plugin-Version", "1.0.0")
            mainAttributes.putValue("Plugin-Api-Version", "3.2.0")
            mainAttributes.putValue("Plugin-Class", "example.YamlSupportPlugin")
            mainAttributes.putValue("Plugin-Config-Schema", "plugin-schema.json")
            mainAttributes.putValue("Plugin-Default-Config", "plugin-default.yml")
        }
        writeManifestJar(directory.resolve("yaml-support.jar"), manifest, defaultConfiguration)

        val artifact = PluginAdministrationService(directory.toString()).scan(null).items.single()

        assertThat(artifact.status).isEqualTo("DISCOVERED")
        assertThat(artifact.defaultConfigContent).isEqualTo(defaultConfiguration)
        assertThat(artifact.defaultConfigJson).isNull()
        assertThat(artifact.configFormat).isEqualTo("YAML")
        assertThat(artifact.configFileName).isEqualTo("config.yml")
    }

    @Test
    fun `scans default configurations larger than 64 KiB without changing their content`() {
        val defaultConfiguration = "{\"value\":\"${"x".repeat(70_000)}\"}"
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Plugin-Id", "large-support")
            mainAttributes.putValue("Plugin-Name", "Large Support Plugin")
            mainAttributes.putValue("Plugin-Version", "1.0.0")
            mainAttributes.putValue("Plugin-Api-Version", "3.2.0")
            mainAttributes.putValue("Plugin-Class", "example.LargeSupportPlugin")
            mainAttributes.putValue("Plugin-Config-Schema", "plugin-schema.json")
            mainAttributes.putValue("Plugin-Default-Config", "plugin-default.json")
        }
        writeManifestJar(directory.resolve("large-support.jar"), manifest, defaultConfiguration)

        val artifact = PluginAdministrationService(directory.toString()).scan(null).items.single()

        assertThat(artifact.status).isEqualTo("DISCOVERED")
        assertThat(artifact.defaultConfigContent).isEqualTo(defaultConfiguration)
        assertThat(artifact.defaultConfigJson).isEqualTo(defaultConfiguration)
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

    private fun writeManifestJar(target: Path, manifest: Manifest, defaultConfigurationContent: String = "{}") {
        val defaultConfiguration = requireNotNull(manifest.mainAttributes.getValue("Plugin-Default-Config"))
        JarOutputStream(Files.newOutputStream(target), manifest).use { output ->
            output.putNextEntry(ZipEntry("plugin-schema.json"))
            output.write("""{"type":"object"}""".toByteArray(StandardCharsets.UTF_8))
            output.closeEntry()
            output.putNextEntry(ZipEntry(defaultConfiguration))
            output.write(defaultConfigurationContent.toByteArray(StandardCharsets.UTF_8))
            output.closeEntry()
            output.finish()
        }
    }
}
