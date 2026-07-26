package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.ModuleDescriptor
import com.mieai.qqbot.module.spi.FrameworkModule
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle
import com.mieai.qqbot.module.spi.ModuleContext
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource

/** Starts validated external framework modules in dependency order. */
class FrameworkModuleHost(
    discoveredModules: Collection<FrameworkModule>,
    private val artifactRegistry: ModuleArtifactRegistry? = null,
    discoveredLifecycles: Collection<FrameworkModuleLifecycle> = emptyList(),
) : SmartLifecycle {
    private val modules: Map<String, FrameworkModule>
    private val states = mutableMapOf<String, ModuleRuntimeState>()
    private val errors = mutableMapOf<String, String>()
    private val contexts = mutableMapOf<String, DefaultModuleContext>()
    private val services = DefaultModuleServiceRegistry()
    private val started = mutableListOf<FrameworkModule>()
    private var running = false

    init {
        val values = linkedMapOf<String, FrameworkModule>()
        discoveredModules.sortedBy { it.descriptor.id }.forEach { module ->
            val descriptor = module.descriptor
            check(values.putIfAbsent(descriptor.id, module) == null) {
                "Duplicate framework module: ${descriptor.id}"
            }
            states[descriptor.id] = ModuleRuntimeState.DISCOVERED
        }

        if (artifactRegistry != null) {
            values.values.forEach { module ->
                val descriptor = module.descriptor
                val artifact = artifactRegistry.find(descriptor.id)
                    ?: throw IllegalStateException(
                        "FrameworkModule Bean is not backed by an external module JAR: ${descriptor.id}",
                    )
                check(artifact.descriptor == descriptor) {
                    "FrameworkModule Bean descriptor differs from ${artifact.path.fileName} for ${descriptor.id}"
                }
            }

            discoveredLifecycles.forEach { lifecycle ->
                val artifact = artifactRegistry.find(lifecycle.moduleId)
                    ?: throw IllegalStateException(
                        "FrameworkModuleLifecycle Bean is not backed by an external module JAR: ${lifecycle.moduleId}",
                    )
                val adapted = object : FrameworkModule {
                    override val descriptor: ModuleDescriptor = artifact.descriptor

                    override fun start(context: ModuleContext) = lifecycle.start(context)

                    override fun stop() = lifecycle.stop()
                }
                check(values.putIfAbsent(artifact.descriptor.id, adapted) == null) {
                    "Multiple lifecycle Beans for framework module: ${artifact.descriptor.id}"
                }
            }

            artifactRegistry.artifacts().forEach { artifact ->
                values.computeIfAbsent(artifact.descriptor.id) {
                    FrameworkModule.declarative(artifact.descriptor)
                }
                states.putIfAbsent(artifact.descriptor.id, ModuleRuntimeState.DISCOVERED)
            }
        }
        modules = values.toMap()
    }

    @Synchronized
    override fun start() {
        if (running) return
        val order = resolveStartOrder()
        try {
            order.forEach { module ->
                val descriptor = module.descriptor
                val id = descriptor.id
                states[id] = ModuleRuntimeState.STARTING
                val context = DefaultModuleContext(descriptor, services)
                contexts[id] = context
                try {
                    module.start(context)
                    started.add(module)
                    states[id] = ModuleRuntimeState.ACTIVE
                    errors.remove(id)
                    LOGGER.info("Framework module {} {} started", id, descriptor.version)
                } catch (failure: RuntimeException) {
                    context.close()
                    contexts.remove(id)
                    states[id] = ModuleRuntimeState.FAILED
                    errors[id] = safeError(failure)
                    throw IllegalStateException("Unable to start framework module $id", failure)
                }
            }
            running = true
        } catch (failure: RuntimeException) {
            stopStartedModules()
            throw failure
        }
    }

    @Synchronized
    override fun stop() {
        stopStartedModules()
        running = false
    }

    override fun stop(callback: Runnable) {
        try {
            stop()
        } finally {
            callback.run()
        }
    }

    @Synchronized
    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = PHASE

    @Synchronized
    fun snapshots(): List<ModuleRuntimeSnapshot> =
        modules.values
            .map { module ->
                val descriptor = module.descriptor
                ModuleRuntimeSnapshot(
                    descriptor,
                    states.getValue(descriptor.id),
                    errors[descriptor.id],
                )
            }
            .sortedBy { it.descriptor.id }

    @Synchronized
    fun findWebAsset(moduleId: String, assetPath: String?): Resource? {
        val module = modules[moduleId]
        if (module == null || states[moduleId] != ModuleRuntimeState.ACTIVE) return null
        val path = normalizeAssetPath(assetPath)
        artifactRegistry?.let { return it.findWebAsset(moduleId, path) }
        val resourceName = "META-INF/qqbot/modules/$moduleId/web/$path"
        val url = module.javaClass.classLoader.getResource(resourceName)
        return url?.let(::UrlResource)
    }

    fun artifact(moduleId: String): ModuleArtifact? = artifactRegistry?.find(moduleId)

    private fun resolveStartOrder(): List<FrameworkModule> {
        val descriptors = linkedMapOf<String, ModuleDescriptor>()
        modules.forEach { (id, module) -> descriptors[id] = module.descriptor }
        return ModuleGraph.resolve(descriptors).map { modules.getValue(it) }
    }

    private fun stopStartedModules() {
        for (index in started.indices.reversed()) {
            val module = started[index]
            val id = module.descriptor.id
            states[id] = ModuleRuntimeState.STOPPING
            try {
                module.stop()
                states[id] = ModuleRuntimeState.STOPPED
            } catch (failure: RuntimeException) {
                states[id] = ModuleRuntimeState.FAILED
                errors[id] = safeError(failure)
                LOGGER.warn(
                    "Framework module {} failed while stopping ({})",
                    id,
                    failure.javaClass.simpleName,
                )
            } finally {
                contexts.remove(id)?.close()
            }
        }
        started.clear()
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(FrameworkModuleHost::class.java)
        const val PHASE = Int.MIN_VALUE + 1_000

        fun normalizeAssetPath(value: String?): String {
            require(value != null) { "assetPath must not be null" }
            val path = if (value.startsWith('/')) value.substring(1) else value
            require(
                path.isNotBlank() &&
                    !path.contains("..") &&
                    !path.contains("\\") &&
                    !path.contains('\u0000'),
            ) {
                "assetPath is invalid"
            }
            return path
        }

        fun safeError(failure: RuntimeException): String {
            val message = failure.message
            return if (message.isNullOrBlank()) failure.javaClass.simpleName else message
        }
    }
}
