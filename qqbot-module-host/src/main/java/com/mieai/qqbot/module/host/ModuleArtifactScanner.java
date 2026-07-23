package com.mieai.qqbot.module.host;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mieai.qqbot.module.api.ModuleDependency;
import com.mieai.qqbot.module.api.ModuleDescriptor;
import com.mieai.qqbot.module.api.ModuleBotSettingsContribution;
import com.mieai.qqbot.module.api.ModuleWebContribution;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ModuleArtifactScanner {
    static final String DESCRIPTOR_PATH = "META-INF/qqbot/module.json";
    private static final String MODULE_RESOURCE_ROOT = "META-INF/qqbot/modules/";
    private static final long MAX_DESCRIPTOR_BYTES = 256 * 1024L;

    private final JsonMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    ModuleArtifactRegistry scan(Path directory) {
        Path resolved = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IllegalStateException("Framework module directory does not exist: " + resolved);
        }

        List<Path> jars;
        try (var entries = Files.list(resolved)) {
            jars = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan framework module directory " + resolved,
                    exception);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("No framework module JARs found in " + resolved);
        }

        Map<String, ModuleArtifact> artifacts = new LinkedHashMap<>();
        for (Path jar : jars) {
            ModuleArtifact artifact = read(jar);
            ModuleArtifact duplicate = artifacts.putIfAbsent(artifact.descriptor().id(), artifact);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate framework module "
                        + artifact.descriptor().id() + " in " + duplicate.path().getFileName()
                        + " and " + artifact.path().getFileName());
            }
        }
        validateGlobalWebContributions(artifacts.values());
        Map<String, ModuleDescriptor> descriptors = new LinkedHashMap<>();
        artifacts.forEach((id, artifact) -> descriptors.put(id, artifact.descriptor()));
        List<String> order = ModuleGraph.resolve(descriptors);
        return new ModuleArtifactRegistry(resolved, artifacts, order);
    }

    private ModuleArtifact read(Path path) {
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            List<JarEntry> descriptors = jar.stream()
                    .filter(entry -> DESCRIPTOR_PATH.equals(entry.getName()))
                    .toList();
            if (descriptors.size() != 1) {
                throw new IllegalStateException("Module JAR " + path.getFileName()
                        + " must contain exactly one " + DESCRIPTOR_PATH);
            }
            JarEntry descriptorEntry = descriptors.getFirst();
            if (descriptorEntry.getSize() > MAX_DESCRIPTOR_BYTES) {
                throw new IllegalStateException("Module descriptor is too large in "
                        + path.getFileName());
            }
            DescriptorDocument document;
            try (InputStream input = jar.getInputStream(descriptorEntry)) {
                byte[] descriptorBytes = input.readNBytes(
                        Math.toIntExact(MAX_DESCRIPTOR_BYTES + 1));
                if (descriptorBytes.length > MAX_DESCRIPTOR_BYTES) {
                    throw new IllegalStateException("Module descriptor is too large in "
                            + path.getFileName());
                }
                document = objectMapper.readValue(descriptorBytes, DescriptorDocument.class);
            }
            ModuleDescriptor descriptor = toDescriptor(document);
            validateOwnedResources(jar, path, descriptor);
            validateWebAssets(jar, path, descriptor);
            return new ModuleArtifact(path, descriptor, sha256(path));
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException("Unable to read framework module JAR " + path, exception);
        }
    }

    private static ModuleDescriptor toDescriptor(DescriptorDocument document) {
        if (document == null || document.schemaVersion() == null
                || document.schemaVersion() != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        List<DependencyDocument> dependencyDocuments = document.dependencies() == null
                ? List.of() : document.dependencies();
        List<String> capabilities = document.capabilities() == null
                ? List.of() : document.capabilities();
        Set<String> uniqueCapabilities = new LinkedHashSet<>(capabilities);
        if (uniqueCapabilities.size() != capabilities.size()) {
            throw new IllegalArgumentException("capabilities contain duplicates");
        }
        List<PageDocument> pages = document.web() == null || document.web().pages() == null
                ? List.of() : document.web().pages();
        List<BotSettingsDocument> botSettings = document.web() == null
                || document.web().botSettings() == null
                ? List.of() : document.web().botSettings();

        List<ModuleDependency> dependencies = dependencyDocuments.stream()
                .map(value -> new ModuleDependency(
                        value.moduleId(), value.minimumVersion(), Boolean.TRUE.equals(value.optional())))
                .toList();
        List<ModuleWebContribution> contributions = pages.stream()
                .map(value -> ModuleWebContribution.webComponent(
                        value.id(), value.label(), value.route(), value.icon(), value.order(),
                        value.entrypoint(), value.customElement()))
                .toList();
        List<ModuleBotSettingsContribution> settingsContributions = botSettings.stream()
                .map(value -> new ModuleBotSettingsContribution(
                        value.id(), value.label(), value.order(),
                        value.entrypoint(), value.customElement()))
                .toList();
        return new ModuleDescriptor(
                document.id(),
                document.name(),
                document.version(),
                document.minimumFrameworkVersion(),
                dependencies,
                uniqueCapabilities,
                contributions,
                settingsContributions);
    }

    private static void validateOwnedResources(
            JarFile jar, Path path, ModuleDescriptor descriptor) {
        String ownPrefix = MODULE_RESOURCE_ROOT + descriptor.id() + "/";
        jar.stream().filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().startsWith(MODULE_RESOURCE_ROOT))
                .filter(entry -> !entry.getName().startsWith(ownPrefix))
                .findFirst()
                .ifPresent(entry -> {
                    throw new IllegalStateException("Module JAR " + path.getFileName()
                            + " contains resources owned by another module: " + entry.getName());
                });
    }

    private static void validateWebAssets(
            JarFile jar, Path path, ModuleDescriptor descriptor) {
        for (ModuleWebContribution contribution : descriptor.webContributions()) {
            String resource = MODULE_RESOURCE_ROOT + descriptor.id() + "/web/"
                    + contribution.assetPath();
            JarEntry entry = jar.getJarEntry(resource);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException("Module JAR " + path.getFileName()
                        + " is missing Web entrypoint " + resource);
            }
        }
        for (ModuleBotSettingsContribution contribution
                : descriptor.botSettingsContributions()) {
            String resource = MODULE_RESOURCE_ROOT + descriptor.id() + "/web/"
                    + contribution.assetPath();
            JarEntry entry = jar.getJarEntry(resource);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException("Module JAR " + path.getFileName()
                        + " is missing Web entrypoint " + resource);
            }
        }
    }

    private static void validateGlobalWebContributions(
            Iterable<ModuleArtifact> artifacts) {
        Set<String> routes = new LinkedHashSet<>();
        Set<String> elements = new LinkedHashSet<>();
        for (ModuleArtifact artifact : artifacts) {
            for (ModuleWebContribution contribution
                    : artifact.descriptor().webContributions()) {
                if (!routes.add(contribution.route())) {
                    throw new IllegalStateException("Duplicate module Web route: "
                            + contribution.route());
                }
                if (!elements.add(contribution.customElement())) {
                    throw new IllegalStateException("Duplicate module custom element: "
                            + contribution.customElement());
                }
            }
            for (ModuleBotSettingsContribution contribution
                    : artifact.descriptor().botSettingsContributions()) {
                if (!elements.add(contribution.customElement())) {
                    throw new IllegalStateException("Duplicate module custom element: "
                            + contribution.customElement());
                }
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record DescriptorDocument(
            Integer schemaVersion,
            String id,
            String name,
            String version,
            String minimumFrameworkVersion,
            List<DependencyDocument> dependencies,
            List<String> capabilities,
            WebDocument web) {}

    private record DependencyDocument(
            String moduleId,
            String minimumVersion,
            Boolean optional) {}

    private record WebDocument(
            List<PageDocument> pages,
            List<BotSettingsDocument> botSettings) {}

    private record PageDocument(
            String id,
            String label,
            String route,
            String icon,
            int order,
            String entrypoint,
            String customElement) {}

    private record BotSettingsDocument(
            String id,
            String label,
            int order,
            String entrypoint,
            String customElement) {}
}
