package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.ModuleDescriptor;
import com.mieai.qqbot.module.spi.FrameworkModule;
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Starts validated external framework modules in dependency order. */
public final class FrameworkModuleHost implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(FrameworkModuleHost.class);
    private static final int PHASE = Integer.MIN_VALUE + 1_000;

    private final Map<String, FrameworkModule> modules;
    private final ModuleArtifactRegistry artifactRegistry;
    private final Map<String, ModuleRuntimeState> states = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, DefaultModuleContext> contexts = new HashMap<>();
    private final DefaultModuleServiceRegistry services = new DefaultModuleServiceRegistry();
    private final List<FrameworkModule> started = new ArrayList<>();
    private boolean running;

    public FrameworkModuleHost(Collection<FrameworkModule> discoveredModules) {
        this(null, discoveredModules, List.of());
    }

    public FrameworkModuleHost(
            ModuleArtifactRegistry artifactRegistry,
            Collection<FrameworkModule> discoveredModules) {
        this(artifactRegistry, discoveredModules, List.of());
    }

    public FrameworkModuleHost(
            ModuleArtifactRegistry artifactRegistry,
            Collection<FrameworkModule> discoveredModules,
            Collection<FrameworkModuleLifecycle> discoveredLifecycles) {
        Objects.requireNonNull(discoveredModules, "discoveredModules must not be null");
        Objects.requireNonNull(discoveredLifecycles, "discoveredLifecycles must not be null");
        this.artifactRegistry = artifactRegistry;
        Map<String, FrameworkModule> values = new LinkedHashMap<>();
        discoveredModules.stream()
                .sorted(Comparator.comparing(module -> module.descriptor().id()))
                .forEach(module -> {
                    Objects.requireNonNull(module, "framework module must not be null");
                    ModuleDescriptor descriptor = Objects.requireNonNull(
                            module.descriptor(), "module descriptor must not be null");
                    if (values.putIfAbsent(descriptor.id(), module) != null) {
                        throw new IllegalStateException("Duplicate framework module: " + descriptor.id());
                    }
                    states.put(descriptor.id(), ModuleRuntimeState.DISCOVERED);
                });
        if (artifactRegistry != null) {
            for (FrameworkModule module : values.values()) {
                ModuleArtifact artifact = artifactRegistry.find(module.descriptor().id())
                        .orElseThrow(() -> new IllegalStateException(
                                "FrameworkModule Bean is not backed by an external module JAR: "
                                        + module.descriptor().id()));
                if (!artifact.descriptor().equals(module.descriptor())) {
                    throw new IllegalStateException("FrameworkModule Bean descriptor differs from "
                            + artifact.path().getFileName() + " for " + module.descriptor().id());
                }
            }
            for (FrameworkModuleLifecycle lifecycle : discoveredLifecycles) {
                Objects.requireNonNull(lifecycle, "framework module lifecycle must not be null");
                ModuleArtifact artifact = artifactRegistry.find(lifecycle.moduleId())
                        .orElseThrow(() -> new IllegalStateException(
                                "FrameworkModuleLifecycle Bean is not backed by an external module JAR: "
                                        + lifecycle.moduleId()));
                FrameworkModule adapted = new FrameworkModule() {
                    @Override public ModuleDescriptor descriptor() {
                        return artifact.descriptor();
                    }

                    @Override public void start(com.mieai.qqbot.module.spi.ModuleContext context) {
                        lifecycle.start(context);
                    }

                    @Override public void stop() {
                        lifecycle.stop();
                    }
                };
                if (values.putIfAbsent(artifact.descriptor().id(), adapted) != null) {
                    throw new IllegalStateException("Multiple lifecycle Beans for framework module: "
                            + artifact.descriptor().id());
                }
            }
            for (ModuleArtifact artifact : artifactRegistry.artifacts()) {
                values.computeIfAbsent(artifact.descriptor().id(), ignored ->
                        FrameworkModule.declarative(artifact.descriptor()));
                states.putIfAbsent(artifact.descriptor().id(), ModuleRuntimeState.DISCOVERED);
            }
        }
        modules = Map.copyOf(values);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        List<FrameworkModule> order = resolveStartOrder();
        try {
            for (FrameworkModule module : order) {
                String id = module.descriptor().id();
                states.put(id, ModuleRuntimeState.STARTING);
                DefaultModuleContext context = new DefaultModuleContext(module.descriptor(), services);
                contexts.put(id, context);
                try {
                    module.start(context);
                    started.add(module);
                    states.put(id, ModuleRuntimeState.ACTIVE);
                    errors.remove(id);
                    LOGGER.info("Framework module {} {} started", id, module.descriptor().version());
                } catch (RuntimeException failure) {
                    context.close();
                    contexts.remove(id);
                    states.put(id, ModuleRuntimeState.FAILED);
                    errors.put(id, safeError(failure));
                    throw new IllegalStateException("Unable to start framework module " + id, failure);
                }
            }
            running = true;
        } catch (RuntimeException failure) {
            stopStartedModules();
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        stopStartedModules();
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override public synchronized boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return PHASE; }

    public synchronized List<ModuleRuntimeSnapshot> snapshots() {
        return modules.values().stream()
                .map(module -> new ModuleRuntimeSnapshot(module.descriptor(),
                        states.get(module.descriptor().id()),
                        Optional.ofNullable(errors.get(module.descriptor().id()))))
                .sorted(Comparator.comparing(snapshot -> snapshot.descriptor().id()))
                .toList();
    }

    public synchronized Optional<Resource> findWebAsset(String moduleId, String assetPath) {
        FrameworkModule module = modules.get(moduleId);
        if (module == null || states.get(moduleId) != ModuleRuntimeState.ACTIVE) return Optional.empty();
        String path = normalizeAssetPath(assetPath);
        if (artifactRegistry != null) {
            return artifactRegistry.findWebAsset(moduleId, path);
        }
        String resourceName = "META-INF/qqbot/modules/" + moduleId + "/web/" + path;
        URL url = module.getClass().getClassLoader().getResource(resourceName);
        return url == null ? Optional.empty() : Optional.of(new org.springframework.core.io.UrlResource(url));
    }

    public Optional<ModuleArtifact> artifact(String moduleId) {
        return artifactRegistry == null ? Optional.empty() : artifactRegistry.find(moduleId);
    }

    private List<FrameworkModule> resolveStartOrder() {
        Map<String, ModuleDescriptor> descriptors = new LinkedHashMap<>();
        modules.forEach((id, module) -> descriptors.put(id, module.descriptor()));
        return ModuleGraph.resolve(descriptors).stream().map(modules::get).toList();
    }

    private void stopStartedModules() {
        for (int index = started.size() - 1; index >= 0; index--) {
            FrameworkModule module = started.get(index);
            String id = module.descriptor().id();
            states.put(id, ModuleRuntimeState.STOPPING);
            try {
                module.stop();
                states.put(id, ModuleRuntimeState.STOPPED);
            } catch (RuntimeException failure) {
                states.put(id, ModuleRuntimeState.FAILED);
                errors.put(id, safeError(failure));
                LOGGER.warn("Framework module {} failed while stopping ({})",
                        id, failure.getClass().getSimpleName());
            } finally {
                DefaultModuleContext context = contexts.remove(id);
                if (context != null) context.close();
            }
        }
        started.clear();
    }

    private static String normalizeAssetPath(String value) {
        if (value == null) throw new IllegalArgumentException("assetPath must not be null");
        String path = value.startsWith("/") ? value.substring(1) : value;
        if (path.isBlank() || path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("assetPath is invalid");
        }
        return path;
    }

    private static String safeError(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
