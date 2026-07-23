package com.mieai.qqbot.module.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.module.api.ModuleDependency;
import com.mieai.qqbot.module.api.ModuleDescriptor;
import com.mieai.qqbot.module.api.ModuleServiceKey;
import com.mieai.qqbot.module.api.ModuleServiceRegistration;
import com.mieai.qqbot.module.spi.FrameworkModule;
import com.mieai.qqbot.module.spi.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FrameworkModuleHostTest {
    private static final ModuleServiceKey<GreetingService> GREETING =
            ModuleServiceKey.of("test.greeting", GreetingService.class);

    @Test
    void startsInDependencyOrderAndAllowsDeclaredServiceAccess() {
        List<String> events = new ArrayList<>();
        FrameworkModule provider = module("provider", List.of(), new Lifecycle() {
            @Override public void start(ModuleContext context) {
                events.add("start-provider");
                context.publish(GREETING, () -> "ready");
            }
            @Override public void stop() { events.add("stop-provider"); }
        });
        FrameworkModule consumer = module("consumer",
                List.of(ModuleDependency.required("provider", "1.0.0")), new Lifecycle() {
                    @Override public void start(ModuleContext context) {
                        events.add("start-consumer-" + context.require(GREETING).value());
                    }
                    @Override public void stop() { events.add("stop-consumer"); }
                });

        FrameworkModuleHost host = new FrameworkModuleHost(List.of(consumer, provider));
        host.start();

        assertThat(events).containsExactly("start-provider", "start-consumer-ready");
        assertThat(host.snapshots()).allMatch(snapshot -> snapshot.state() == ModuleRuntimeState.ACTIVE);

        host.stop();
        assertThat(events).containsExactly("start-provider", "start-consumer-ready",
                "stop-consumer", "stop-provider");
    }

    @Test
    void rejectsMissingDependenciesAndCyclesBeforeStartingCode() {
        FrameworkModule missing = module("missing-consumer",
                List.of(ModuleDependency.required("absent", "1.0.0")), new Lifecycle() {});
        assertThatThrownBy(() -> new FrameworkModuleHost(List.of(missing)).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires missing module absent");

        FrameworkModule first = module("first",
                List.of(ModuleDependency.required("second", "1.0.0")), new Lifecycle() {});
        FrameworkModule second = module("second",
                List.of(ModuleDependency.required("first", "1.0.0")), new Lifecycle() {});
        assertThatThrownBy(() -> new FrameworkModuleHost(List.of(first, second)).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dependency cycle");
    }

    @Test
    void rejectsInsufficientDependencyVersionsAndUndeclaredServiceAccess() {
        FrameworkModule provider = module("provider", "1.0.0", List.of(), new Lifecycle() {
            @Override public void start(ModuleContext context) {
                context.publish(GREETING, () -> "ready");
            }
        });
        FrameworkModule incompatible = module("incompatible",
                List.of(ModuleDependency.required("provider", "2.0.0")), new Lifecycle() {});
        assertThatThrownBy(() -> new FrameworkModuleHost(List.of(provider, incompatible)).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires provider 2.0.0 or newer");

        FrameworkModule undeclared = module("undeclared", List.of(), new Lifecycle() {
            @Override public void start(ModuleContext context) {
                context.require(GREETING);
            }
        });
        assertThatThrownBy(() -> new FrameworkModuleHost(List.of(provider, undeclared)).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to start framework module undeclared")
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessageContaining("did not declare a dependency on service owner provider");
    }

    @Test
    void rollsBackStartedModulesAndRevokesTheirServicesWhenStartupFails() {
        List<String> events = new ArrayList<>();
        AtomicReference<ModuleServiceRegistration> registration = new AtomicReference<>();
        FrameworkModule provider = module("provider", List.of(), new Lifecycle() {
            @Override public void start(ModuleContext context) {
                events.add("start-provider");
                registration.set(context.publish(GREETING, () -> "ready"));
            }
            @Override public void stop() { events.add("stop-provider"); }
        });
        FrameworkModule failing = module("failing",
                List.of(ModuleDependency.required("provider", "1.0.0")), new Lifecycle() {
                    @Override public void start(ModuleContext context) {
                        events.add("start-failing");
                        throw new IllegalStateException("planned failure");
                    }
                });
        FrameworkModuleHost host = new FrameworkModuleHost(List.of(provider, failing));

        assertThatThrownBy(host::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to start framework module failing");
        assertThat(events).containsExactly("start-provider", "start-failing", "stop-provider");
        assertThat(registration.get().isActive()).isFalse();
        assertThat(host.isRunning()).isFalse();
        assertThat(host.snapshots())
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.descriptor().id()).isEqualTo("failing");
                    assertThat(snapshot.state()).isEqualTo(ModuleRuntimeState.FAILED);
                })
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.descriptor().id()).isEqualTo("provider");
                    assertThat(snapshot.state()).isEqualTo(ModuleRuntimeState.STOPPED);
                });
    }

    @Test
    void servesOnlyAssetsOwnedByAnActiveModule() {
        FrameworkModule module = module("asset-owner", List.of(), new Lifecycle() {});
        FrameworkModuleHost host = new FrameworkModuleHost(List.of(module));

        assertThat(host.findWebAsset("asset-owner", "main.js")).isEmpty();
        host.start();
        assertThat(host.findWebAsset("asset-owner", "main.js")).isPresent();
        assertThatThrownBy(() -> host.findWebAsset("asset-owner", "../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        host.stop();
        assertThat(host.findWebAsset("asset-owner", "main.js")).isEmpty();
    }

    private static FrameworkModule module(
            String id, List<ModuleDependency> dependencies, Lifecycle lifecycle) {
        return module(id, "1.0.0", dependencies, lifecycle);
    }

    private static FrameworkModule module(
            String id, String version, List<ModuleDependency> dependencies, Lifecycle lifecycle) {
        ModuleDescriptor descriptor = new ModuleDescriptor(id, id, version, "1.0.0",
                dependencies, java.util.Set.of(), List.of(), List.of());
        return new FrameworkModule() {
            @Override public ModuleDescriptor descriptor() { return descriptor; }
            @Override public void start(ModuleContext context) { lifecycle.start(context); }
            @Override public void stop() { lifecycle.stop(); }
        };
    }

    private interface GreetingService { String value(); }

    private interface Lifecycle {
        default void start(ModuleContext context) {}
        default void stop() {}
    }
}
