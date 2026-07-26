package com.mieai.qqbot.module.host

import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URL
import java.nio.file.Path
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/** Validates external modules before Spring selects their auto-configurations. */
class ModuleEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        var configured = environment.getProperty("qqbot.modules.directory")
        if (configured.isNullOrBlank()) configured = environment.getProperty("QQBOT_MODULES_DIR")
        if (configured.isNullOrBlank()) return

        ModuleArtifactRegistry.load(Path.of(configured)).use { registry ->
            verifyModuleClasspath(registry)
            val properties = linkedMapOf<String, Any>()
            properties["qqbot.modules.directory"] = registry.directory().toString()
            properties["qqbot.modules.active-ids"] = registry.artifacts()
                .map { it.descriptor.id }
                .sorted()
            registry.artifacts().forEach { artifact ->
                properties["qqbot.modules.available.${artifact.descriptor.id}"] = "true"
            }
            environment.propertySources.addFirst(MapPropertySource(PROPERTY_SOURCE, properties))
        }
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

    private fun verifyModuleClasspath(registry: ModuleArtifactRegistry) {
        val classLoader = Thread.currentThread().contextClassLoader
            ?: ModuleEnvironmentPostProcessor::class.java.classLoader
        val descriptors = linkedMapOf<String, MutableList<URL>>()
        val versions = linkedMapOf<String, String>()
        try {
            val resources = classLoader.getResources(ModuleArtifactScanner.DESCRIPTOR_PATH)
            val mapper = JsonMapper.builder().build()
            while (resources.hasMoreElements()) {
                val resource = resources.nextElement()
                val id = resource.openStream().use { input ->
                    val descriptor = mapper.readTree(input)
                    val value = descriptor.path("id").asText()
                    versions.putIfAbsent(value, descriptor.path("version").asText())
                    value
                }
                descriptors.getOrPut(id) { mutableListOf() }.add(resource)
            }
        } catch (error: IOException) {
            throw IllegalStateException(
                "Unable to inspect framework modules on the startup classpath",
                error,
            )
        }

        descriptors.forEach { (id, resources) ->
            check(id.isNotBlank()) {
                "A framework module descriptor on the startup classpath has no id"
            }
            check(resources.size == 1) {
                "Framework module $id appears multiple times on the startup classpath: $resources"
            }
        }

        val expected = registry.artifacts().mapTo(linkedSetOf()) { it.descriptor.id }
        val actual = descriptors.keys.toCollection(linkedSetOf())
        if (actual != expected) {
            val missing = expected.toMutableSet().apply { removeAll(actual) }
            val unexpected = actual.toMutableSet().apply { removeAll(expected) }
            throw IllegalStateException(
                "Framework module startup classpath does not match ${registry.directory()}; " +
                    "missing=$missing, unexpected=$unexpected. " +
                    "Configure loader.path/LOADER_PATH to the module directory.",
            )
        }

        registry.artifacts().forEach { artifact ->
            val descriptor = artifact.descriptor
            val classpathVersion = versions[descriptor.id]
            check(descriptor.version == classpathVersion) {
                "Framework module ${descriptor.id} has version ${descriptor.version} in " +
                    "${registry.directory()} but version $classpathVersion on the startup classpath"
            }
        }
    }

    companion object {
        internal const val PROPERTY_SOURCE = "qqbotValidatedModules"
    }
}
