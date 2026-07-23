package com.mieai.qqbot.module.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleArtifactRegistryTest {
    @TempDir
    private Path directory;

    @Test
    void bindsLifecycleCallbacksToTheJarDescriptor() throws Exception {
        writeModule("lifecycle.jar", descriptor("lifecycle", "1.0.0", "", ""), Map.of());
        java.util.List<String> events = new java.util.ArrayList<>();

        try (ModuleArtifactRegistry registry = ModuleArtifactRegistry.load(directory)) {
            FrameworkModuleLifecycle lifecycle = new FrameworkModuleLifecycle() {
                @Override public String moduleId() { return "lifecycle"; }
                @Override public void start(com.mieai.qqbot.module.spi.ModuleContext context) {
                    events.add("start-" + context.moduleId());
                }
                @Override public void stop() { events.add("stop"); }
            };
            FrameworkModuleHost host = new FrameworkModuleHost(
                    registry, java.util.List.of(), java.util.List.of(lifecycle));

            host.start();
            host.stop();
        }

        assertThat(events).containsExactly("start-lifecycle", "stop");
    }

    @Test
    void loadsDescriptorHashOrderAndAssetFromTheOwningJar() throws Exception {
        writeModule("provider.jar", descriptor("provider", "1.2.0", "", ""), Map.of());
        writeModule("consumer.jar", descriptor(
                "consumer",
                "1.0.0",
                "{\"moduleId\":\"provider\",\"minimumVersion\":\"1.1.0\",\"optional\":false}",
                "{\"id\":\"page\",\"label\":\"Page\","
                        + "\"route\":\"/modules/consumer/page\",\"icon\":\"chart\","
                        + "\"order\":10,\"entrypoint\":\"main.js\","
                        + "\"customElement\":\"qqbot-consumer-page\"}"),
                Map.of("META-INF/qqbot/modules/consumer/web/main.js",
                        "customElements.define('qqbot-consumer-page', class extends HTMLElement {});"));

        try (ModuleArtifactRegistry registry = ModuleArtifactRegistry.load(directory)) {
            assertThat(registry.startOrder())
                    .extracting(artifact -> artifact.descriptor().id())
                    .containsExactly("provider", "consumer");
            assertThat(registry.require("consumer").sha256()).matches("[0-9a-f]{64}");
            assertThat(registry.findWebAsset("consumer", "main.js")).isPresent();
            assertThat(registry.findWebAsset("provider", "main.js")).isEmpty();
            assertThatThrownBy(() -> registry.findWebAsset("consumer", "../secret"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void loadsBotSettingsContributionsWithoutCreatingNavigationRoutes() throws Exception {
        String descriptor = descriptor("settings", "1.0.0", "", "")
                .replace("\"web\": { \"pages\": [] }", """
                        \"web\": {
                            \"pages\": [],
                            \"botSettings\": [{
                                \"id\": \"connection\",
                                \"label\": \"Connection\",
                                \"order\": 20,
                                \"entrypoint\": \"settings.js\",
                                \"customElement\": \"qqbot-settings-connection\"
                            }]
                        }""");
        writeModule("settings.jar", descriptor,
                Map.of("META-INF/qqbot/modules/settings/web/settings.js",
                        "customElements.define('qqbot-settings-connection', class extends HTMLElement {});"));

        try (ModuleArtifactRegistry registry = ModuleArtifactRegistry.load(directory)) {
            var loaded = registry.require("settings").descriptor();
            assertThat(loaded.webContributions()).isEmpty();
            assertThat(loaded.botSettingsContributions()).singleElement()
                    .extracting(value -> value.customElement())
                    .isEqualTo("qqbot-settings-connection");
        }
    }

    @Test
    void rejectsMissingDescriptorsDuplicateIdsAndForeignResources() throws Exception {
        writeJar(directory.resolve("missing.jar"), Map.of("payload.txt", "invalid"));
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("exactly one META-INF/qqbot/module.json");

        java.nio.file.Files.delete(directory.resolve("missing.jar"));
        writeModule("one.jar", descriptor("same", "1.0.0", "", ""), Map.of());
        writeModule("two.jar", descriptor("same", "1.0.0", "", ""), Map.of());
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("Duplicate framework module same");

        java.nio.file.Files.delete(directory.resolve("two.jar"));
        java.nio.file.Files.delete(directory.resolve("one.jar"));
        writeModule("foreign.jar", descriptor("owner", "1.0.0", "", ""),
                Map.of("META-INF/qqbot/modules/not-owner/web/main.js", "invalid"));
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("resources owned by another module");
    }

    @Test
    void rejectsMissingOrIncompatibleDependenciesAndCycles() throws Exception {
        writeModule("consumer.jar", descriptor(
                "consumer", "1.0.0",
                "{\"moduleId\":\"missing\",\"minimumVersion\":\"1.0.0\",\"optional\":false}",
                ""), Map.of());
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("requires missing module missing");

        java.nio.file.Files.delete(directory.resolve("consumer.jar"));
        writeModule("provider.jar", descriptor("provider", "1.0.0", "", ""), Map.of());
        writeModule("consumer.jar", descriptor(
                "consumer", "1.0.0",
                "{\"moduleId\":\"provider\",\"minimumVersion\":\"2.0.0\",\"optional\":false}",
                ""), Map.of());
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("requires provider 2.0.0 or newer");

        java.nio.file.Files.delete(directory.resolve("provider.jar"));
        java.nio.file.Files.delete(directory.resolve("consumer.jar"));
        writeModule("first.jar", descriptor(
                "first", "1.0.0",
                "{\"moduleId\":\"second\",\"minimumVersion\":\"1.0.0\",\"optional\":false}",
                ""), Map.of());
        writeModule("second.jar", descriptor(
                "second", "1.0.0",
                "{\"moduleId\":\"first\",\"minimumVersion\":\"1.0.0\",\"optional\":false}",
                ""), Map.of());
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("dependency cycle");
    }

    @Test
    void rejectsUnsupportedFrameworkVersionsAndMissingWebEntrypoints() throws Exception {
        String incompatible = descriptor("future", "1.0.0", "", "")
                .replace("\"minimumFrameworkVersion\": \"1.0.0\"",
                        "\"minimumFrameworkVersion\": \"9.0.0\"");
        writeModule("future.jar", incompatible, Map.of());
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("requires framework 9.0.0");

        java.nio.file.Files.delete(directory.resolve("future.jar"));
        writeModule("missing-web.jar", descriptor(
                "missing-web",
                "1.0.0",
                "",
                "{\"id\":\"page\",\"label\":\"Page\","
                        + "\"route\":\"/modules/missing-web/page\",\"icon\":\"chart\","
                        + "\"order\":10,\"entrypoint\":\"main.js\","
                        + "\"customElement\":\"qqbot-missing-web-page\"}"), Map.of());
        assertThatThrownBy(() -> ModuleArtifactRegistry.load(directory))
                .hasMessageContaining("is missing Web entrypoint");
    }

    private void writeModule(
            String fileName, String descriptor, Map<String, String> resources) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ModuleArtifactScanner.DESCRIPTOR_PATH, descriptor);
        entries.putAll(resources);
        writeJar(directory.resolve(fileName), entries);
    }

    private static void writeJar(Path path, Map<String, String> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(
                java.nio.file.Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static String descriptor(
            String id, String version, String dependency, String page) {
        String dependencies = dependency.isBlank() ? "" : dependency;
        String pages = page.isBlank() ? "" : page;
        return """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "minimumFrameworkVersion": "1.0.0",
                  "dependencies": [%s],
                  "capabilities": [],
                  "web": { "pages": [%s] }
                }
                """.formatted(id, id, version, dependencies, pages);
    }
}
