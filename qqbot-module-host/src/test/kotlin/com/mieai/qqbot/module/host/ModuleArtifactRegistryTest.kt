package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle
import com.mieai.qqbot.module.spi.ModuleContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModuleArtifactRegistryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun bindsLifecycleCallbacksToTheJarDescriptor() {
        writeModule("lifecycle.jar", descriptor("lifecycle", "1.0.0", "", ""), emptyMap())
        val events = mutableListOf<String>()

        ModuleArtifactRegistry.load(directory).use { registry ->
            val lifecycle = object : FrameworkModuleLifecycle {
                override val moduleId: String = "lifecycle"
                override fun start(context: ModuleContext) { events += "start-${context.moduleId}" }
                override fun stop() { events += "stop" }
            }
            val host = FrameworkModuleHost(
                discoveredModules = emptyList(),
                artifactRegistry = registry,
                discoveredLifecycles = listOf(lifecycle),
            )
            host.start()
            host.stop()
        }

        assertThat(events).containsExactly("start-lifecycle", "stop")
    }

    @Test
    fun loadsDescriptorHashOrderAndAssetFromTheOwningJar() {
        writeModule("provider.jar", descriptor("provider", "1.2.0", "", ""), emptyMap())
        writeModule(
            "consumer.jar",
            descriptor(
                "consumer", "1.0.0",
                "{\"moduleId\":\"provider\",\"minimumVersion\":\"1.1.0\",\"optional\":false}",
                "{\"id\":\"page\",\"label\":\"Page\",\"route\":\"/modules/consumer/page\",\"icon\":\"chart\",\"order\":10,\"entrypoint\":\"main.js\",\"customElement\":\"qqbot-consumer-page\"}",
            ),
            mapOf(
                "META-INF/qqbot/modules/consumer/web/main.js" to
                    "customElements.define('qqbot-consumer-page', class extends HTMLElement {});",
            ),
        )

        ModuleArtifactRegistry.load(directory).use { registry ->
            assertThat(registry.startOrder().map { it.descriptor.id })
                .containsExactly("provider", "consumer")
            assertThat(registry.require("consumer").sha256).matches("[0-9a-f]{64}")
            assertThat(registry.findWebAsset("consumer", "main.js")).isNotNull()
            assertThat(registry.findWebAsset("provider", "main.js")).isNull()
            assertThatThrownBy { registry.findWebAsset("consumer", "../secret") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun loadsBotSettingsContributionsWithoutCreatingNavigationRoutes() {
        val document = descriptor("settings", "1.0.0", "", "").replace(
            "\"web\": { \"pages\": [] }",
            """"web": {
                "pages": [],
                "botSettings": [{
                    "id": "connection",
                    "label": "Connection",
                    "order": 20,
                    "entrypoint": "settings.js",
                    "customElement": "qqbot-settings-connection"
                }]
            }""".trimIndent(),
        )
        writeModule(
            "settings.jar", document,
            mapOf(
                "META-INF/qqbot/modules/settings/web/settings.js" to
                    "customElements.define('qqbot-settings-connection', class extends HTMLElement {});",
            ),
        )

        ModuleArtifactRegistry.load(directory).use { registry ->
            val loaded = registry.require("settings").descriptor
            assertThat(loaded.webContributions).isEmpty()
            assertThat(loaded.botSettingsContributions.single().customElement)
                .isEqualTo("qqbot-settings-connection")
        }
    }

    @Test
    fun rejectsMissingDescriptorsDuplicateIdsAndForeignResources() {
        writeJar(directory.resolve("missing.jar"), mapOf("payload.txt" to "invalid"))
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("exactly one META-INF/qqbot/module.json")

        Files.delete(directory.resolve("missing.jar"))
        writeModule("one.jar", descriptor("same", "1.0.0", "", ""), emptyMap())
        writeModule("two.jar", descriptor("same", "1.0.0", "", ""), emptyMap())
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("Duplicate framework module same")

        Files.delete(directory.resolve("two.jar"))
        Files.delete(directory.resolve("one.jar"))
        writeModule(
            "foreign.jar", descriptor("owner", "1.0.0", "", ""),
            mapOf("META-INF/qqbot/modules/not-owner/web/main.js" to "invalid"),
        )
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("resources owned by another module")
    }

    @Test
    fun rejectsMissingOrIncompatibleDependenciesAndCycles() {
        writeModule(
            "consumer.jar", descriptor(
                "consumer", "1.0.0",
                "{\"moduleId\":\"missing\",\"minimumVersion\":\"1.0.0\",\"optional\":false}", "",
            ), emptyMap(),
        )
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("requires missing module missing")

        Files.delete(directory.resolve("consumer.jar"))
        writeModule("provider.jar", descriptor("provider", "1.0.0", "", ""), emptyMap())
        writeModule(
            "consumer.jar", descriptor(
                "consumer", "1.0.0",
                "{\"moduleId\":\"provider\",\"minimumVersion\":\"2.0.0\",\"optional\":false}", "",
            ), emptyMap(),
        )
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("requires provider 2.0.0 or newer")

        Files.delete(directory.resolve("provider.jar"))
        Files.delete(directory.resolve("consumer.jar"))
        writeModule(
            "first.jar", descriptor(
                "first", "1.0.0",
                "{\"moduleId\":\"second\",\"minimumVersion\":\"1.0.0\",\"optional\":false}", "",
            ), emptyMap(),
        )
        writeModule(
            "second.jar", descriptor(
                "second", "1.0.0",
                "{\"moduleId\":\"first\",\"minimumVersion\":\"1.0.0\",\"optional\":false}", "",
            ), emptyMap(),
        )
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("dependency cycle")
    }

    @Test
    fun rejectsUnsupportedFrameworkVersionsAndMissingWebEntrypoints() {
        val incompatible = descriptor("future", "1.0.0", "", "").replace(
            "\"minimumFrameworkVersion\": \"1.0.0\"", "\"minimumFrameworkVersion\": \"9.0.0\"",
        )
        writeModule("future.jar", incompatible, emptyMap())
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("requires framework 9.0.0")

        Files.delete(directory.resolve("future.jar"))
        writeModule(
            "missing-web.jar", descriptor(
                "missing-web", "1.0.0", "",
                "{\"id\":\"page\",\"label\":\"Page\",\"route\":\"/modules/missing-web/page\",\"icon\":\"chart\",\"order\":10,\"entrypoint\":\"main.js\",\"customElement\":\"qqbot-missing-web-page\"}",
            ), emptyMap(),
        )
        assertThatThrownBy { ModuleArtifactRegistry.load(directory) }
            .hasMessageContaining("is missing Web entrypoint")
    }

    private fun writeModule(fileName: String, descriptor: String, resources: Map<String, String>) {
        writeJar(directory.resolve(fileName), linkedMapOf(ModuleArtifactScanner.DESCRIPTOR_PATH to descriptor).apply { putAll(resources) })
    }

    private fun writeJar(path: Path, entries: Map<String, String>) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(JarEntry(name))
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
    }

    private fun descriptor(id: String, version: String, dependency: String, page: String): String {
        val dependencies = dependency.takeUnless(String::isBlank).orEmpty()
        val pages = page.takeUnless(String::isBlank).orEmpty()
        return """
            {
              "schemaVersion": 1,
              "id": "$id",
              "name": "$id",
              "version": "$version",
              "minimumFrameworkVersion": "1.0.0",
              "dependencies": [$dependencies],
              "capabilities": [],
              "web": { "pages": [$pages] }
            }
        """.trimIndent()
    }
}
