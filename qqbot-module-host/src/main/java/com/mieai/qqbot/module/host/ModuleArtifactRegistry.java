package com.mieai.qqbot.module.host;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

/** Validated module artifacts in dependency order. */
public final class ModuleArtifactRegistry implements AutoCloseable {
    static {
        URLConnection.setDefaultUseCaches("jar", false);
    }

    private final Path directory;
    private final Map<String, ModuleArtifact> artifacts;
    private final List<String> startOrder;
    private final Map<String, URLClassLoader> artifactClassLoaders = new ConcurrentHashMap<>();
    private final Map<String, JarFile> openedJars = new ConcurrentHashMap<>();

    ModuleArtifactRegistry(
            Path directory, Map<String, ModuleArtifact> artifacts, List<String> startOrder) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.artifacts = Map.copyOf(new LinkedHashMap<>(artifacts));
        this.startOrder = List.copyOf(startOrder);
    }

    public static ModuleArtifactRegistry load(Path directory) {
        return new ModuleArtifactScanner().scan(directory);
    }

    public Path directory() {
        return directory;
    }

    public List<ModuleArtifact> artifacts() {
        return artifacts.values().stream()
                .sorted(java.util.Comparator.comparing(value -> value.descriptor().id()))
                .toList();
    }

    public List<ModuleArtifact> startOrder() {
        return startOrder.stream().map(artifacts::get).toList();
    }

    public Optional<ModuleArtifact> find(String moduleId) {
        return Optional.ofNullable(artifacts.get(moduleId));
    }

    public ModuleArtifact require(String moduleId) {
        return find(moduleId).orElseThrow(() -> new IllegalArgumentException(
                "Unknown framework module: " + moduleId));
    }

    public Optional<Resource> findWebAsset(String moduleId, String assetPath) {
        ModuleArtifact artifact = artifacts.get(moduleId);
        if (artifact == null) return Optional.empty();
        String path = normalizeAssetPath(assetPath);
        String resourceName = "META-INF/qqbot/modules/" + moduleId + "/web/" + path;
        JarFile jar = openJar(artifact);
        JarEntry entry = jar.getJarEntry(resourceName);
        if (entry == null || entry.isDirectory()) return Optional.empty();
        return Optional.of(new JarEntryResource(
                jar, entry, artifact.path().getFileName().toString()));
    }

    public ClassLoader artifactClassLoader(String moduleId) {
        ModuleArtifact artifact = require(moduleId);
        return artifactClassLoaders.computeIfAbsent(moduleId, ignored -> {
            try {
                return new URLClassLoader(
                        new URL[] {artifact.path().toUri().toURL()},
                        ModuleArtifactRegistry.class.getClassLoader());
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to create class loader for " + moduleId,
                        exception);
            }
        });
    }

    public boolean containsResourcePrefix(String moduleId, String resourcePrefix) {
        ModuleArtifact artifact = require(moduleId);
        String expectedPrefix = "META-INF/qqbot/modules/" + moduleId + "/";
        if (resourcePrefix == null || !resourcePrefix.startsWith(expectedPrefix)
                || resourcePrefix.contains("..") || resourcePrefix.contains("\\")) {
            throw new IllegalArgumentException("Module resource prefix is invalid");
        }
        try {
            JarFile jar = openJar(artifact);
            return jar.stream().anyMatch(entry -> !entry.isDirectory()
                    && entry.getName().startsWith(resourcePrefix));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to inspect resources for " + moduleId,
                    exception);
        }
    }

    @Override
    public void close() {
        List<IOException> failures = new ArrayList<>();
        artifactClassLoaders.values().forEach(loader -> {
            try {
                loader.close();
            } catch (IOException exception) {
                failures.add(exception);
            }
        });
        artifactClassLoaders.clear();
        openedJars.values().forEach(jar -> {
            try {
                jar.close();
            } catch (IOException exception) {
                failures.add(exception);
            }
        });
        openedJars.clear();
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "Unable to close module artifact class loaders", failures.getFirst());
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static String normalizeAssetPath(String value) {
        if (value == null) throw new IllegalArgumentException("assetPath must not be null");
        String path = value.startsWith("/") ? value.substring(1) : value;
        if (path.isBlank() || path.contains("..") || path.contains("\\")
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("assetPath is invalid");
        }
        return path;
    }

    private JarFile openJar(ModuleArtifact artifact) {
        return openedJars.computeIfAbsent(artifact.descriptor().id(), ignored -> {
            try {
                return new JarFile(artifact.path().toFile(), true);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to open framework module JAR "
                        + artifact.path(), exception);
            }
        });
    }

    private static final class JarEntryResource extends AbstractResource {
        private final JarFile jar;
        private final JarEntry entry;
        private final String artifactName;

        private JarEntryResource(JarFile jar, JarEntry entry, String artifactName) {
            this.jar = jar;
            this.entry = entry;
            this.artifactName = artifactName;
        }

        @Override
        public String getDescription() {
            return artifactName + "!/" + entry.getName();
        }

        @Override
        public String getFilename() {
            int separator = entry.getName().lastIndexOf('/');
            return separator < 0 ? entry.getName() : entry.getName().substring(separator + 1);
        }

        @Override
        public long contentLength() {
            return entry.getSize();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return jar.getInputStream(entry);
        }
    }
}
