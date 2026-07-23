package com.mieai.qqbot.module.host;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Validates external modules before Spring selects their auto-configurations. */
public final class ModuleEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE = "qqbotValidatedModules";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("qqbot.modules.directory");
        if (configured == null || configured.isBlank()) {
            configured = environment.getProperty("QQBOT_MODULES_DIR");
        }
        if (configured == null || configured.isBlank()) {
            return;
        }
        try (ModuleArtifactRegistry registry = ModuleArtifactRegistry.load(Path.of(configured))) {
            verifyModuleClasspath(registry);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("qqbot.modules.directory", registry.directory().toString());
            properties.put("qqbot.modules.active-ids", registry.artifacts().stream()
                    .map(artifact -> artifact.descriptor().id())
                    .sorted()
                    .toList());
            registry.artifacts().forEach(artifact -> properties.put(
                    "qqbot.modules.available." + artifact.descriptor().id(), "true"));
            environment.getPropertySources().addFirst(
                    new MapPropertySource(PROPERTY_SOURCE, properties));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private static void verifyModuleClasspath(ModuleArtifactRegistry registry) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = ModuleEnvironmentPostProcessor.class.getClassLoader();
        Map<String, List<URL>> descriptors = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(
                    ModuleArtifactScanner.DESCRIPTOR_PATH);
            JsonMapper mapper = JsonMapper.builder().build();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String id;
                try (var input = resource.openStream()) {
                    var descriptor = mapper.readTree(input);
                    id = descriptor.path("id").asText();
                    versions.putIfAbsent(id, descriptor.path("version").asText());
                }
                descriptors.computeIfAbsent(id, ignored -> new ArrayList<>()).add(resource);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect framework modules on the startup classpath",
                    exception);
        }
        descriptors.forEach((id, resources) -> {
            if (id.isBlank()) {
                throw new IllegalStateException(
                        "A framework module descriptor on the startup classpath has no id");
            }
            if (resources.size() != 1) {
                throw new IllegalStateException("Framework module " + id
                        + " appears multiple times on the startup classpath: " + resources);
            }
        });
        Set<String> expected = registry.artifacts().stream()
                .map(artifact -> artifact.descriptor().id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actual = new LinkedHashSet<>(descriptors.keySet());
        if (!actual.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalStateException("Framework module startup classpath does not match "
                    + registry.directory() + "; missing=" + missing + ", unexpected=" + unexpected
                    + ". Configure loader.path/LOADER_PATH to the module directory.");
        }
        registry.artifacts().forEach(artifact -> {
            String classpathVersion = versions.get(artifact.descriptor().id());
            if (!artifact.descriptor().version().equals(classpathVersion)) {
                throw new IllegalStateException("Framework module " + artifact.descriptor().id()
                        + " has version " + artifact.descriptor().version() + " in "
                        + registry.directory() + " but version " + classpathVersion
                        + " on the startup classpath");
            }
        });
    }
}
