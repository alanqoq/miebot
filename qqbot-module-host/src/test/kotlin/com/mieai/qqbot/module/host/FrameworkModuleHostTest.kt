package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.ModuleDependency
import com.mieai.qqbot.module.api.ModuleDescriptor
import com.mieai.qqbot.module.api.ModuleServiceKey
import com.mieai.qqbot.module.api.ModuleServiceRegistration
import com.mieai.qqbot.module.spi.FrameworkModule
import com.mieai.qqbot.module.spi.ModuleContext
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FrameworkModuleHostTest {
    @Test
    fun startsInDependencyOrderAndAllowsDeclaredServiceAccess() {
        val events = mutableListOf<String>()
        val provider = module("provider", emptyList(), object : Lifecycle {
            override fun start(context: ModuleContext) {
                events += "start-provider"
                context.publish(GREETING, GreetingService { "ready" })
            }

            override fun stop() { events += "stop-provider" }
        })
        val consumer = module(
            "consumer",
            listOf(ModuleDependency.required("provider", "1.0.0")),
            object : Lifecycle {
                override fun start(context: ModuleContext) {
                    events += "start-consumer-${context.require(GREETING).value()}"
                }

                override fun stop() { events += "stop-consumer" }
            },
        )

        val host = FrameworkModuleHost(listOf(consumer, provider))
        host.start()

        assertThat(events).containsExactly("start-provider", "start-consumer-ready")
        assertThat(host.snapshots()).allMatch { it.state == ModuleRuntimeState.ACTIVE }
        host.stop()
        assertThat(events).containsExactly(
            "start-provider", "start-consumer-ready", "stop-consumer", "stop-provider",
        )
    }

    @Test
    fun rejectsMissingDependenciesAndCyclesBeforeStartingCode() {
        val missing = module(
            "missing-consumer",
            listOf(ModuleDependency.required("absent", "1.0.0")),
            object : Lifecycle {},
        )
        assertThatThrownBy { FrameworkModuleHost(listOf(missing)).start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires missing module absent")

        val first = module(
            "first", listOf(ModuleDependency.required("second", "1.0.0")), object : Lifecycle {},
        )
        val second = module(
            "second", listOf(ModuleDependency.required("first", "1.0.0")), object : Lifecycle {},
        )
        assertThatThrownBy { FrameworkModuleHost(listOf(first, second)).start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("dependency cycle")
    }

    @Test
    fun rejectsInsufficientDependencyVersionsAndUndeclaredServiceAccess() {
        val provider = module("provider", "1.0.0", emptyList(), object : Lifecycle {
            override fun start(context: ModuleContext) {
                context.publish(GREETING, GreetingService { "ready" })
            }
        })
        val incompatible = module(
            "incompatible", listOf(ModuleDependency.required("provider", "2.0.0")), object : Lifecycle {},
        )
        assertThatThrownBy { FrameworkModuleHost(listOf(provider, incompatible)).start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires provider 2.0.0 or newer")

        val undeclared = module("undeclared", emptyList(), object : Lifecycle {
            override fun start(context: ModuleContext) {
                context.require(GREETING)
            }
        })
        assertThatThrownBy { FrameworkModuleHost(listOf(provider, undeclared)).start() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unable to start framework module undeclared")
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .cause()
            .hasMessageContaining("did not declare a dependency on service owner provider")
    }

    @Test
    fun rollsBackStartedModulesAndRevokesTheirServicesWhenStartupFails() {
        val events = mutableListOf<String>()
        val registration = AtomicReference<ModuleServiceRegistration>()
        val provider = module("provider", emptyList(), object : Lifecycle {
            override fun start(context: ModuleContext) {
                events += "start-provider"
                registration.set(context.publish(GREETING, GreetingService { "ready" }))
            }

            override fun stop() { events += "stop-provider" }
        })
        val failing = module(
            "failing", listOf(ModuleDependency.required("provider", "1.0.0")), object : Lifecycle {
                override fun start(context: ModuleContext) {
                    events += "start-failing"
                    throw IllegalStateException("planned failure")
                }
            },
        )
        val host = FrameworkModuleHost(listOf(provider, failing))

        assertThatThrownBy(host::start)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unable to start framework module failing")
        assertThat(events).containsExactly("start-provider", "start-failing", "stop-provider")
        assertThat(registration.get().isActive).isFalse()
        assertThat(host.isRunning()).isFalse()
        val snapshots = host.snapshots().associateBy { it.descriptor.id }
        assertThat(snapshots.getValue("failing").state).isEqualTo(ModuleRuntimeState.FAILED)
        assertThat(snapshots.getValue("provider").state).isEqualTo(ModuleRuntimeState.STOPPED)
    }

    @Test
    fun servesOnlyAssetsOwnedByAnActiveModule() {
        val module = module("asset-owner", emptyList(), object : Lifecycle {})
        val host = FrameworkModuleHost(listOf(module))

        assertThat(host.findWebAsset("asset-owner", "main.js")).isNull()
        host.start()
        assertThat(host.findWebAsset("asset-owner", "main.js")).isNotNull()
        assertThatThrownBy { host.findWebAsset("asset-owner", "../secret") }
            .isInstanceOf(IllegalArgumentException::class.java)
        host.stop()
        assertThat(host.findWebAsset("asset-owner", "main.js")).isNull()
    }

    private fun module(id: String, dependencies: List<ModuleDependency>, lifecycle: Lifecycle): FrameworkModule =
        module(id, "1.0.0", dependencies, lifecycle)

    private fun module(
        id: String,
        version: String,
        dependencies: List<ModuleDependency>,
        lifecycle: Lifecycle,
    ): FrameworkModule {
        val descriptor = ModuleDescriptor.create(
            id,
            id,
            version,
            "1.0.0",
            dependencies,
        )
        return object : FrameworkModule {
            override val descriptor: ModuleDescriptor = descriptor
            override fun start(context: ModuleContext) = lifecycle.start(context)
            override fun stop() = lifecycle.stop()
        }
    }

    private fun interface GreetingService {
        fun value(): String
    }

    private interface Lifecycle {
        fun start(context: ModuleContext) {}
        fun stop() {}
    }

    private companion object {
        val GREETING = ModuleServiceKey("test.greeting", GreetingService::class.java)
    }
}
