package com.mieai.qqbot.admin.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginAdministrationServiceTest {
    @TempDir
    Path directory;

    @Test
    void scansManifestAndHashWithoutLoadingPluginCode() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Plugin-Id", "support");
        manifest.getMainAttributes().putValue("Plugin-Name", "Support Plugin");
        manifest.getMainAttributes().putValue("Plugin-Version", "1.2.3");
        manifest.getMainAttributes().putValue("Plugin-Api-Version", "1");
        manifest.getMainAttributes().putValue("Plugin-Class", "example.SupportPlugin");
        manifest.getMainAttributes().putValue("Plugin-Config-Schema", "plugin-schema.json");
        manifest.getMainAttributes().putValue("Plugin-Default-Config", "plugin-default.json");
        writeManifestJar(directory.resolve("support.jar"), manifest);

        PluginInventoryResponse response = new PluginAdministrationService(directory.toString()).scan(null);

        assertThat(response.directoryExists()).isTrue();
        assertThat(response.runtimeAvailable()).isFalse();
        assertThat(response.items()).singleElement().satisfies(artifact -> {
            assertThat(artifact.id()).isEqualTo("support");
            assertThat(artifact.name()).isEqualTo("Support Plugin");
            assertThat(artifact.version()).isEqualTo("1.2.3");
            assertThat(artifact.status()).isEqualTo("DISCOVERED");
            assertThat(artifact.loaded()).isFalse();
            assertThat(artifact.sha256()).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void rejectsOversizedOrControlCharacterQueryBeforeReadingDirectory() {
        PluginAdministrationService service = new PluginAdministrationService(directory.toString());

        assertThatThrownBy(() -> service.scan("x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.scan("bad\nquery"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsMissingDirectoryWithoutCreatingIt() {
        Path missing = directory.resolve("missing");

        PluginInventoryResponse response = new PluginAdministrationService(missing.toString()).scan(null);

        assertThat(response.directoryExists()).isFalse();
        assertThat(response.items()).isEmpty();
        assertThat(Files.exists(missing)).isFalse();
    }

    private static void writeManifestJar(Path target, Manifest manifest) throws IOException {
        JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest);
        output.putNextEntry(new ZipEntry("plugin-schema.json"));
        output.write("{\"type\":\"object\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
        output.putNextEntry(new ZipEntry("plugin-default.json"));
        output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
        output.finish();
        output.close();
    }
}
