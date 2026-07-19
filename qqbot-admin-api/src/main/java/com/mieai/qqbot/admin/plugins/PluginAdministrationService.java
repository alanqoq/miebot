package com.mieai.qqbot.admin.plugins;

import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.plugin.host.Pf4jPluginHost;
import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * Scans the trusted, operator-mounted plugin directory without loading or executing plugin code.
 *
 * <p>Filesystem metadata is overlaid with the live PF4J host state and binding counts.
 */
@Service
public class PluginAdministrationService {
    private static final int MAX_ARTIFACTS = 500;
    private static final long MAX_HASH_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;
    private static final String STATUS_DISCOVERED = "DISCOVERED";
    private static final String STATUS_INVALID = "INVALID";
    private static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

    private final Path pluginDirectory;
    private final Pf4jPluginHost host;
    private final BotPluginBindingRepository bindings;
    private final PluginRuntimeService runtime;

    public PluginAdministrationService(
            @Value("${qqbot.plugins.directory:/plugins}") String pluginDirectory) {
        this(pluginDirectory, (Pf4jPluginHost) null, (BotPluginBindingRepository) null,
                (PluginRuntimeService) null);
    }

    @Autowired
    public PluginAdministrationService(
            @Value("${qqbot.plugins.directory:/plugins}") String pluginDirectory,
            ObjectProvider<Pf4jPluginHost> host,
            ObjectProvider<BotPluginBindingRepository> bindings,
            ObjectProvider<PluginRuntimeService> runtime) {
        this(pluginDirectory, host.getIfAvailable(), bindings.getIfAvailable(), runtime.getIfAvailable());
    }

    private PluginAdministrationService(String pluginDirectory, Pf4jPluginHost host,
            BotPluginBindingRepository bindings, PluginRuntimeService runtime) {
        if (pluginDirectory == null || pluginDirectory.isBlank()) {
            throw new IllegalArgumentException("pluginDirectory must not be blank");
        }
        this.pluginDirectory = Path.of(pluginDirectory).toAbsolutePath().normalize();
        this.host = host;
        this.bindings = bindings;
        this.runtime = runtime;
    }

    public PluginInventoryResponse scan(String query) {
        Instant scannedAt = Instant.now();
        String normalizedQuery = normalizeQuery(query);
        if (Files.notExists(pluginDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return new PluginInventoryResponse(
                    List.of(),
                    pluginDirectory.toString(),
                    false,
                    host != null && host.isStarted(),
                    null,
                    scannedAt);
        }
        if (!Files.isDirectory(pluginDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return new PluginInventoryResponse(
                    List.of(),
                    pluginDirectory.toString(),
                    false,
                    host != null && host.isStarted(),
                    "插件路径不是目录",
                    scannedAt);
        }

        List<PluginArtifactResponse> artifacts = new ArrayList<>();
        Map<String, Integer> bindingCounts = new java.util.HashMap<>();
        Map<String, Integer> enabledBindingCounts = new java.util.HashMap<>();
        if (bindings != null) {
            bindings.findAll().forEach(binding -> {
                bindingCounts.merge(binding.pluginId(), 1, Integer::sum);
                if (binding.enabled()) enabledBindingCounts.merge(binding.pluginId(), 1, Integer::sum);
            });
        }
        String scanError = null;
        try (var paths = Files.list(pluginDirectory)) {
            List<Path> jars = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_ARTIFACTS)
                    .toList();
            for (Path jar : jars) {
                PluginArtifactResponse artifact = decorate(inspect(jar), bindingCounts, enabledBindingCounts);
                if (normalizedQuery == null || matches(artifact, normalizedQuery)) {
                    artifacts.add(artifact);
                }
            }
        } catch (IOException exception) {
            scanError = "无法读取插件目录：" + safeMessage(exception);
        }
        return new PluginInventoryResponse(
                artifacts,
                pluginDirectory.toString(),
                true,
                host != null && host.isStarted(),
                scanError,
                scannedAt);
    }

    public void reload() {
        if (runtime == null) throw new IllegalStateException("Plugin runtime is not available");
        runtime.reloadPlugins();
    }

    public PluginUploadResponse upload(MultipartFile file) {
        if (runtime == null || host == null || !host.isStarted()) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "PLUGIN_RUNTIME_UNAVAILABLE",
                    "Plugin runtime is not available");
        }
        if (file == null || file.isEmpty()) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_REQUIRED", "请选择要上传的插件 JAR");
        }
        String originalName = safeUploadName(file.getOriginalFilename());
        if (!originalName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_TYPE_INVALID", "只允许上传 .jar 插件制品");
        }
        if (file.getSize() < 1L || file.getSize() > MAX_UPLOAD_BYTES) {
            throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_FILE_TOO_LARGE", "插件 JAR 不能超过 64 MiB");
        }

        Path stagingDirectory = pluginDirectory.resolve(".staging").normalize();
        Path staged = stagingDirectory.resolve(java.util.UUID.randomUUID() + ".jar").normalize();
        if (!staged.getParent().equals(stagingDirectory)) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "插件文件名无效");
        }
        try {
            Files.createDirectories(stagingDirectory);
            try (InputStream input = new BufferedInputStream(file.getInputStream());
                    var output = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                long written = 0L;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > MAX_UPLOAD_BYTES) {
                        throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_FILE_TOO_LARGE",
                                "插件 JAR 不能超过 64 MiB");
                    }
                    output.write(buffer, 0, count);
                }
            }
            var result = runtime.installArtifact(staged);
            PluginArtifactResponse artifact = scan(null).items().stream()
                    .filter(value -> value.id().equals(result.artifact().id())
                            && value.sha256().equals(result.artifact().sha256()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Installed plugin is missing from inventory"));
            return PluginUploadResponse.from(result, artifact);
        } catch (PluginAdministrationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_UPLOAD_IO_FAILED", "无法保存上传的插件 JAR");
        } catch (RuntimeException exception) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_ARTIFACT_INVALID",
                    "插件校验或热加载失败：" + safeMessage(exception));
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
    }

    private PluginArtifactResponse decorate(PluginArtifactResponse artifact,
            Map<String, Integer> bindingCounts, Map<String, Integer> enabledBindingCounts) {
        boolean loaded = host != null && host.isLoaded(artifact.id(), artifact.sha256());
        return new PluginArtifactResponse(artifact.id(), artifact.name(), artifact.version(),
                artifact.apiCompatibility(), artifact.fileName(), artifact.sizeBytes(), artifact.modifiedAt(),
                artifact.sha256(), loaded ? "LOADED" : artifact.status(), loaded ? null : artifact.error(), loaded,
                bindingCounts.getOrDefault(artifact.id(), 0), enabledBindingCounts.getOrDefault(artifact.id(), 0));
    }

    private PluginArtifactResponse inspect(Path jar) {
        String fileName = jar.getFileName().toString();
        long size;
        Instant modifiedAt;
        try {
            size = Files.size(jar);
            FileTime modified = Files.getLastModifiedTime(jar, LinkOption.NOFOLLOW_LINKS);
            modifiedAt = modified.toInstant();
        } catch (IOException exception) {
            return invalid(fileName, "无法读取文件属性");
        }

        String hash;
        try {
            hash = sha256(jar, size);
        } catch (IOException exception) {
            return invalid(fileName, "无法计算 SHA-256");
        }

        try (JarFile jarFile = new JarFile(jar.toFile(), false)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return new PluginArtifactResponse(
                        fileName,
                        fileName,
                        "",
                        "",
                        fileName,
                        size,
                        modifiedAt,
                        hash,
                        STATUS_INVALID,
                        "JAR 缺少 MANIFEST.MF",
                        false,
                        0,
                        0);
            }
            var attributes = manifest.getMainAttributes();
            String id = firstNonBlank(
                    attributes.getValue("Plugin-Id"),
                    attributes.getValue("Implementation-Id"),
                    stripJarExtension(fileName));
            String name = firstNonBlank(
                    attributes.getValue("Plugin-Name"),
                    attributes.getValue("Implementation-Title"),
                    id);
            String version = firstNonBlank(
                    attributes.getValue("Plugin-Version"),
                    attributes.getValue("Implementation-Version"),
                    "");
            String apiCompatibility = firstNonBlank(
                    attributes.getValue("Plugin-Api-Version"),
                    attributes.getValue("Plugin-Requires"),
                    "");
            String entrypoint = firstNonBlank(
                    attributes.getValue("Plugin-Class"),
                    attributes.getValue("Plugin-Entry"),
                    null);
            String configurationSchema = attributes.getValue("Plugin-Config-Schema");
            boolean supported = entrypoint != null
                    && configurationSchema != null && !configurationSchema.isBlank();
            String status = supported ? STATUS_DISCOVERED : STATUS_UNSUPPORTED;
            String error = entrypoint == null
                    ? "Manifest 未声明插件入口"
                    : configurationSchema == null || configurationSchema.isBlank()
                            ? "Manifest 未声明配置 Schema"
                            : null;
            return new PluginArtifactResponse(
                    id,
                    name,
                    version,
                    apiCompatibility,
                    fileName,
                    size,
                    modifiedAt,
                    hash,
                    status,
                    error,
                    false,
                    0,
                    0);
        } catch (IOException | SecurityException exception) {
            return invalid(fileName, "无法读取 JAR manifest");
        }
    }

    private static String sha256(Path jar, long size) throws IOException {
        if (size > MAX_HASH_BYTES) {
            throw new IOException("文件超过 512 MiB 扫描上限");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long read = 0;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(
                    jar, StandardOpenOption.READ))) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    read += count;
                    if (read > MAX_HASH_BYTES) {
                        throw new IOException("文件超过 512 MiB 扫描上限");
                    }
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static PluginArtifactResponse invalid(String fileName, String error) {
        return new PluginArtifactResponse(
                fileName,
                fileName,
                "",
                "",
                fileName,
                0,
                Instant.EPOCH,
                "",
                STATUS_INVALID,
                error,
                false,
                0,
                0);
    }

    private static boolean matches(PluginArtifactResponse artifact, String query) {
        return List.of(
                        artifact.id(),
                        artifact.name(),
                        artifact.version(),
                        artifact.fileName(),
                        artifact.status())
                .stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains(query));
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String value = query.strip().toLowerCase(java.util.Locale.ROOT);
        if (value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("query is invalid");
        }
        return value;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return fallback;
    }

    private static String stripJarExtension(String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 256 ? message.substring(0, 256) : message;
    }

    private static String safeUploadName(String value) {
        if (value == null || value.isBlank()) return "plugin.jar";
        String name;
        try { name = Path.of(value).getFileName().toString(); }
        catch (RuntimeException exception) { throw failure(HttpStatus.BAD_REQUEST,
                "PLUGIN_FILE_NAME_INVALID", "插件文件名无效"); }
        if (name.length() > 255 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw failure(HttpStatus.BAD_REQUEST, "PLUGIN_FILE_NAME_INVALID", "插件文件名无效");
        }
        return name;
    }

    private static PluginAdministrationException failure(HttpStatus status, String code, String message) {
        return new PluginAdministrationException(status, code, message);
    }
}
