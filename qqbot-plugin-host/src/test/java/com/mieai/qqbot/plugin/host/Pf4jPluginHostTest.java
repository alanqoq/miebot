package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.JdbcBotRepository;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository;
import com.mieai.qqbot.persistence.inbox.IncomingEvent;
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxQuery;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginStorageRepository;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer;
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pf4jPluginHostTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String BOT = "550e8400-e29b-41d4-a716-446655440001";

    @TempDir Path temporaryDirectory;

    @Test
    void loadsPureJavaSpiPluginAndEnqueuesItsReply() throws Exception {
        Path pluginDirectory = temporaryDirectory.resolve("plugins");
        Path pluginDataRoot = temporaryDirectory.resolve("plugin-data");
        Files.createDirectories(pluginDirectory);
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Files.copy(example, pluginDirectory.resolve(example.getFileName()));

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        insertBot(dataSource);
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        var outbox = new JdbcOutboxRepository(dataSource);
        var inbox = new JdbcEventInboxRepository(dataSource);
        var bindingRepository = new JdbcBotPluginBindingRepository(dataSource);
        var storage = new JdbcPluginStorageRepository(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, pluginDataRoot, artifacts,
                new JdbcBotRepository(dataSource), outbox, storage, new ObjectMapper(), clock, Runnable::run)) {
            host.start();
            assertThat(host.isLoaded("echo")).isTrue();
            assertThat(host.loadedPlugins()).singleElement().satisfies(metadata ->
                    {
                        assertThat(metadata.capabilities()).contains("http");
                        assertThat(metadata.defaultConfigurationPath()).isEqualTo("qqbot-plugin-default.json");
                        assertThat(metadata.defaultConfiguration()).isEqualTo("{}");
                    });
            assertThat(host.defaultConfiguration("echo")).isEqualTo("{}");
            assertThat(artifacts.findById("echo")).isPresent();
            Path missingDefault = pluginWithoutManifestAttribute(example,
                    temporaryDirectory.resolve("invalid-plugin/echo.jar"), "Plugin-Default-Config");
            assertThatThrownBy(() -> host.validateArtifact(missingDefault))
                    .hasMessageContaining("must declare Plugin-Default-Config");

            BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), "echo", BotId.parse(BOT),
                    true, 0, NOW, NOW);
            writeConfiguration(host, binding, "{}");
            assertThat(host.bindingDataDirectory(binding))
                    .isEqualTo(pluginDataRoot.resolve(BOT).resolve("echo").toAbsolutePath().normalize());
            bindingRepository.insert(binding);
            assertThat(host.handlerIds(binding, "C2C_MESSAGE_CREATE"))
                    .containsExactly("commands", "audit");
            IncomingEvent incoming = new IncomingEvent(UUID.randomUUID(), BotEnvironment.SANDBOX,
                    BotId.parse(BOT), "C2C_MESSAGE_CREATE", "message-1",
                    "{\"id\":\"event-1\",\"d\":{\"id\":\"message-1\",\"content\":\"/ping\",\"author\":{\"user_openid\":\"user-1\"}}}", NOW);
            InboxEvent event = inbox.insertOrGet(incoming).event();

            host.execute(binding, event).toCompletableFuture().join();

            var jobs = outbox.query(OutboxQuery.firstPage(10)).jobs();
            assertThat(jobs).singleElement().satisfies(job -> {
                var stored = outbox.findById(job.id()).orElseThrow();
                assertThat(stored.jobType()).isEqualTo(OutboundTextPayload.JOB_TYPE);
                assertThat(stored.sourceEventId()).contains(event.id());
                assertThat(stored.payload()).contains("pong").contains("user-1");
            });

            BotPluginBinding secondBinding = new BotPluginBinding(UUID.randomUUID(), "echo",
                    BotId.parse("550e8400-e29b-41d4-a716-446655440002"), true, 0, NOW, NOW);
            writeConfiguration(host, secondBinding, "{}");
            insertBot(dataSource, secondBinding.botId().toString(), "10002");
            bindingRepository.insert(secondBinding);
            InboxEvent rememberedFirst = inbox.insertOrGet(new IncomingEvent(UUID.randomUUID(),
                    BotEnvironment.SANDBOX, BotId.parse(BOT), "C2C_MESSAGE_CREATE", "remember-1",
                    "{\"id\":\"remember-1\",\"d\":{\"id\":\"message-2\",\"content\":\"/remember\",\"author\":{\"user_openid\":\"user-1\"}}}", NOW)).event();
            InboxEvent rememberedSecond = inbox.insertOrGet(new IncomingEvent(UUID.randomUUID(),
                    BotEnvironment.SANDBOX, secondBinding.botId(), "C2C_MESSAGE_CREATE", "remember-2",
                    "{\"id\":\"remember-2\",\"d\":{\"id\":\"message-3\",\"content\":\"/remember\",\"author\":{\"user_openid\":\"user-2\"}}}", NOW)).event();
            host.execute(binding, rememberedFirst).toCompletableFuture().join();
            host.execute(secondBinding, rememberedSecond).toCompletableFuture().join();
            assertThat(storage.find(binding.id(), "echo", "last-event-id")).contains("remember-1");
            assertThat(storage.find(secondBinding.id(), "echo", "last-event-id")).contains("remember-2");

            host.invalidateBot(binding.botId());
            assertThat(activeBindingIds(host)).containsExactly(secondBinding.id());

            BotPluginBinding invalidBinding = new BotPluginBinding(UUID.randomUUID(), "echo",
                    BotId.parse("550e8400-e29b-41d4-a716-446655440003"), true, 0, NOW, NOW);
            assertThatThrownBy(() -> host.handlerIds(invalidBinding))
                    .hasMessageContaining("data directory does not exist");
            writeConfiguration(host, invalidBinding, "[]");
            assertThatThrownBy(() -> host.handlerIds(invalidBinding))
                    .hasMessageContaining("must be a JSON object");
            writeConfiguration(host, invalidBinding, "{\"unexpected\":true}");
            assertThatThrownBy(() -> host.handlerIds(invalidBinding))
                    .hasMessageContaining("failed schema validation")
                    .hasMessageContaining("$.unexpected is not allowed");
        }
    }

    @Test
    void reconcilesDeletedDisabledPausedChangedAndRevisedInstancesWithoutCreatingNewOnes() throws Exception {
        Path pluginDirectory = temporaryDirectory.resolve("reconcile-plugins");
        Path pluginDataRoot = temporaryDirectory.resolve("reconcile-plugin-data");
        Files.createDirectories(pluginDirectory);
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Files.copy(example, pluginDirectory.resolve(example.getFileName()));

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("reconcile-host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        insertBot(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, pluginDataRoot,
                new JdbcPluginArtifactRepository(dataSource), new JdbcBotRepository(dataSource),
                new JdbcOutboxRepository(dataSource), new JdbcPluginStorageRepository(dataSource),
                new ObjectMapper(), clock, Runnable::run)) {
            host.start();
            BotPluginBinding retained = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            BotPluginBinding deleted = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            BotPluginBinding disabled = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            BotPluginBinding paused = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            BotPluginBinding renamed = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            BotPluginBinding revised = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            BotPluginBinding notMaterialized = binding(UUID.randomUUID(), "echo", true, 1,
                    PluginBindingRuntimeState.ACTIVE);
            List<BotPluginBinding> materialized = List.of(retained, deleted, disabled, paused, renamed, revised);
            writeConfiguration(host, retained, "{}");
            materialized.forEach(host::handlerIds);
            assertThat(activeBindingIds(host)).containsExactlyInAnyOrderElementsOf(
                    materialized.stream().map(BotPluginBinding::id).toList());

            host.reconcileBindings(List.of(
                    retained,
                    binding(disabled.id(), "echo", false, 1, PluginBindingRuntimeState.ACTIVE),
                    binding(paused.id(), "echo", true, 1, PluginBindingRuntimeState.PAUSED),
                    binding(renamed.id(), "renamed", true, 1, PluginBindingRuntimeState.ACTIVE),
                    binding(revised.id(), "echo", true, 2, PluginBindingRuntimeState.ACTIVE),
                    notMaterialized));

            assertThat(activeBindingIds(host)).containsExactly(retained.id());
        }
    }

    @Test
    void rejectsDefaultConfigurationResourcesLargerThan64KiBDuringValidationAndLoading() throws Exception {
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        byte[] oversizedJson = ("{\n" + " ".repeat(64 * 1024) + "\n}")
                .getBytes(StandardCharsets.UTF_8);
        Path oversizedArtifact = pluginWithResource(example,
                temporaryDirectory.resolve("oversized-staging/echo.jar"),
                "qqbot-plugin-default.json", oversizedJson);
        Path pluginDirectory = temporaryDirectory.resolve("oversized-plugins");
        Files.createDirectories(pluginDirectory);

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("oversized-host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory,
                temporaryDirectory.resolve("oversized-plugin-data"), artifacts,
                new JdbcBotRepository(dataSource), new JdbcOutboxRepository(dataSource),
                new JdbcPluginStorageRepository(dataSource), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run)) {
            assertThatThrownBy(() -> host.validateArtifact(oversizedArtifact))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot exceed 64 KiB");

            Files.copy(oversizedArtifact, pluginDirectory.resolve("echo.jar"));
            host.start();

            assertThat(host.isLoaded("echo")).isFalse();
            assertThat(artifacts.findById("echo")).isEmpty();
        }
    }

    @Test
    void rejectsNonCanonicalPluginIdsBeforeStartingTheArtifact() throws Exception {
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        List<String> invalidIds = List.of("Echo", "a/../echo", "echo/", "echo.", "con", "con.settings");

        try (Pf4jPluginHost host = validationHost("invalid-id")) {
            for (int index = 0; index < invalidIds.size(); index++) {
                String pluginId = invalidIds.get(index);
                Path candidate = pluginWithManifestValue(example,
                        temporaryDirectory.resolve("invalid-id-" + index + "/echo.jar"),
                        "Plugin-Id", pluginId);

                assertThatThrownBy(() -> host.validateArtifact(candidate))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Plugin id");
            }
        }
    }

    @Test
    void requiresSchemaAndDefaultConfigurationToBelongToTheCurrentJar() throws Exception {
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Path missingSchema = pluginWithManifestValue(example,
                temporaryDirectory.resolve("parent-schema/echo.jar"),
                "Plugin-Config-Schema", "parent-only-plugin-resource.json");
        Path missingDefault = pluginWithManifestValue(example,
                temporaryDirectory.resolve("parent-default/echo.jar"),
                "Plugin-Default-Config", "parent-only-plugin-resource.json");

        assertThat(Pf4jPluginHostTest.class.getClassLoader()
                .getResource("parent-only-plugin-resource.json")).isNotNull();
        try (Pf4jPluginHost host = validationHost("own-resources")) {
            assertThatThrownBy(() -> host.validateArtifact(missingSchema))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema resource is missing");
            assertThatThrownBy(() -> host.validateArtifact(missingDefault))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("default configuration resource is missing");
        }
    }

    @Test
    void enforcesRuntimeConfigurationUtf8AndExact64KiBBoundary() throws Exception {
        Path pluginDirectory = temporaryDirectory.resolve("configuration-plugins");
        Path pluginDataRoot = temporaryDirectory.resolve("configuration-plugin-data");
        Files.createDirectories(pluginDirectory);
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Files.copy(example, pluginDirectory.resolve(example.getFileName()));

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("configuration-host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        insertBot(dataSource);
        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, pluginDataRoot,
                new JdbcPluginArtifactRepository(dataSource), new JdbcBotRepository(dataSource),
                new JdbcOutboxRepository(dataSource), new JdbcPluginStorageRepository(dataSource),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run)) {
            host.start();
            BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), "echo", BotId.parse(BOT),
                    true, 0, NOW, NOW);
            Files.createDirectories(host.bindingDataDirectory(binding));

            byte[] atLimit = new byte[64 * 1024];
            Arrays.fill(atLimit, (byte) ' ');
            atLimit[0] = '{';
            atLimit[atLimit.length - 1] = '}';
            Files.write(host.configurationFile(binding), atLimit);
            assertThat(host.handlerIds(binding)).containsExactly("commands", "audit");
            host.invalidate(binding.id());

            Files.write(host.configurationFile(binding), Arrays.copyOf(atLimit, atLimit.length + 1));
            assertThatThrownBy(() -> host.handlerIds(binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot exceed 64 KiB");

            Files.write(host.configurationFile(binding), new byte[] {'{', '"', 'x', '"', ':', '"',
                    (byte) 0xc3, 0x28, '"', '}'});
            assertThatThrownBy(() -> host.handlerIds(binding))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be valid UTF-8");
        }
    }

    @Test
    void strictBindingQuiescenceKeepsTimedOutHandleFencedUntilRetry() throws Exception {
        Path pluginDirectory = temporaryDirectory.resolve("strict-stop-plugins");
        Path pluginDataRoot = temporaryDirectory.resolve("strict-stop-plugin-data");
        Files.createDirectories(pluginDirectory);
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Files.copy(example, pluginDirectory.resolve(example.getFileName()));

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("strict-stop-host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        insertBot(dataSource);
        var inbox = new JdbcEventInboxRepository(dataSource);
        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, pluginDataRoot,
                new JdbcPluginArtifactRepository(dataSource), new JdbcBotRepository(dataSource),
                new JdbcOutboxRepository(dataSource), new JdbcPluginStorageRepository(dataSource),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), 256, Duration.ofMillis(25))) {
            host.start();
            BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), "echo", BotId.parse(BOT),
                    true, 0, NOW, NOW);
            writeConfiguration(host, binding, "{}");
            host.handlerIds(binding);

            CompletableFuture<Void> pending = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            activeResources(host, binding.id()).eventService().subscribe(
                    "blocking", Set.of("C2C_MESSAGE_CREATE"), ignored -> {
                        entered.countDown();
                        return pending;
                    });
            InboxEvent event = inbox.insertOrGet(new IncomingEvent(UUID.randomUUID(),
                    BotEnvironment.SANDBOX, binding.botId(), "C2C_MESSAGE_CREATE", "strict-stop-event",
                    "{\"id\":\"strict-stop-event\",\"d\":{}}", NOW)).event();
            var execution = host.execute(binding, event, "blocking");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> host.quiesceBindingStrict(binding.id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still running");
            assertThat(activeBindingIds(host)).containsExactly(binding.id());

            pending.complete(null);
            execution.toCompletableFuture().join();
            host.quiesceBindingStrict(binding.id());
            assertThat(activeBindingIds(host)).isEmpty();
        }
    }

    @Test
    void upgradesPluginWithoutRestartAndRestoresPreviousArtifactWhenInstallationFails() throws Exception {
        Path pluginDirectory = temporaryDirectory.resolve("hot-plugins");
        Path pluginDataRoot = temporaryDirectory.resolve("hot-plugin-data");
        Path stagingDirectory = pluginDirectory.resolve(".staging");
        Files.createDirectories(stagingDirectory);
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Path oldArtifact = pluginWithVersion(example, pluginDirectory.resolve("echo-old.jar"), "1.0.0");

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("hot-host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        insertBot(dataSource);
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, pluginDataRoot, artifacts,
                new JdbcBotRepository(dataSource), new JdbcOutboxRepository(dataSource),
                new JdbcPluginStorageRepository(dataSource), new ObjectMapper(), clock, Runnable::run)) {
            host.start();
            assertThat(host.loadedPlugins()).singleElement()
                    .extracting(LoadedPluginMetadata::version).isEqualTo("1.0.0");

            BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), "echo", BotId.parse(BOT),
                    true, 0, NOW, NOW);
            writeConfiguration(host, binding, "{}");
            assertThat(host.handlerIds(binding, "C2C_MESSAGE_CREATE"))
                    .containsExactly("commands", "audit");

            Path upgrade = pluginWithVersion(example, stagingDirectory.resolve("echo-upgrade.jar"), "2.0.0");
            PluginArtifactInstallResult result = host.installArtifact(upgrade);

            assertThat(result.operation()).isEqualTo("UPGRADED");
            assertThat(result.previousVersion()).contains("1.0.0");
            assertThat(result.artifact().version()).isEqualTo("2.0.0");
            assertThat(result.artifact().path()).isRegularFile();
            assertThat(oldArtifact).doesNotExist();
            assertThat(upgrade).doesNotExist();
            assertThat(host.handlerIds(binding, "C2C_MESSAGE_CREATE"))
                    .containsExactly("commands", "audit");

            Path identical = stagingDirectory.resolve("echo-identical.jar");
            Files.copy(result.artifact().path(), identical);
            PluginArtifactInstallResult unchanged = host.installArtifact(identical);
            assertThat(unchanged.operation()).isEqualTo("UNCHANGED");
            assertThat(identical).doesNotExist();

            Path rejected = pluginWithVersion(example, stagingDirectory.resolve("echo-rejected.jar"), "3.0.0");
            PluginArtifactCandidate candidate = host.validateArtifact(rejected);
            Path occupiedTarget = pluginDirectory.resolve(
                    "echo-3.0.0-" + candidate.sha256().substring(0, 12) + ".jar");
            Files.createDirectory(occupiedTarget);

            assertThatThrownBy(() -> host.installArtifact(rejected))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("target already exists");
            assertThat(occupiedTarget).isDirectory();
            assertThat(host.loadedPlugins()).singleElement()
                    .extracting(LoadedPluginMetadata::version).isEqualTo("2.0.0");
        }
    }

    private static void insertBot(DataSource dataSource) throws Exception {
        insertBot(dataSource, BOT, "10001");
    }

    private static void insertBot(DataSource dataSource, String botId, String appId) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO bots (id, display_name, app_id, environment, app_secret_ciphertext,
                            app_secret_key_id, intents, shard_index, shard_count, enabled, revision, created_at, updated_at)
                        VALUES (?, 'Echo Bot', ?, 'SANDBOX', 'ciphertext', 'primary', 33554432, 0, 1, 1, 1, ?, ?)
                        """)) {
            statement.setString(1, botId);
            statement.setString(2, appId);
            statement.setString(3, NOW.toString());
            statement.setString(4, NOW.toString());
            statement.executeUpdate();
        }
    }

    private static Path pluginWithVersion(Path source, Path target, String version) throws Exception {
        try (JarFile input = new JarFile(source.toFile(), false)) {
            Manifest manifest = new Manifest(input.getManifest());
            manifest.getMainAttributes().putValue("Plugin-Version", version);
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                Enumeration<JarEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) continue;
                    JarEntry copy = new JarEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    output.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        try (var content = input.getInputStream(entry)) {
                            content.transferTo(output);
                        }
                    }
                    output.closeEntry();
                }
            }
        }
        return target;
    }

    private static Path pluginWithManifestValue(
            Path source, Path target, String attribute, String value) throws Exception {
        Files.createDirectories(target.getParent());
        try (JarFile input = new JarFile(source.toFile(), false)) {
            Manifest manifest = new Manifest(input.getManifest());
            manifest.getMainAttributes().putValue(attribute, value);
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                Enumeration<JarEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) continue;
                    JarEntry copy = new JarEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    output.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        try (var content = input.getInputStream(entry)) {
                            content.transferTo(output);
                        }
                    }
                    output.closeEntry();
                }
            }
        }
        return target;
    }

    private static Path pluginWithoutManifestAttribute(Path source, Path target, String attribute)
            throws Exception {
        Files.createDirectories(target.getParent());
        try (JarFile input = new JarFile(source.toFile(), false)) {
            Manifest manifest = new Manifest(input.getManifest());
            manifest.getMainAttributes().remove(new java.util.jar.Attributes.Name(attribute));
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                Enumeration<JarEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) continue;
                    output.putNextEntry(new JarEntry(entry));
                    if (!entry.isDirectory()) {
                        try (var content = input.getInputStream(entry)) {
                            content.transferTo(output);
                        }
                    }
                    output.closeEntry();
                }
            }
        }
        return target;
    }

    private static Path pluginWithResource(
            Path source, Path target, String resourceName, byte[] replacement) throws Exception {
        Files.createDirectories(target.getParent());
        boolean replaced = false;
        try (JarFile input = new JarFile(source.toFile(), false)) {
            Manifest manifest = new Manifest(input.getManifest());
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                Enumeration<JarEntry> entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) continue;
                    JarEntry copy = new JarEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    output.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        if (resourceName.equals(entry.getName())) {
                            output.write(replacement);
                            replaced = true;
                        } else {
                            try (var content = input.getInputStream(entry)) {
                                content.transferTo(output);
                            }
                        }
                    }
                    output.closeEntry();
                }
            }
        }
        if (!replaced) throw new IllegalArgumentException("JAR resource does not exist: " + resourceName);
        return target;
    }

    private static BotPluginBinding binding(UUID id, String pluginId, boolean enabled, long revision,
            PluginBindingRuntimeState runtimeState) {
        return new BotPluginBinding(id, pluginId, BotId.parse(BOT), enabled, revision, NOW, NOW,
                runtimeState, java.util.Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> activeBindingIds(Pf4jPluginHost host) throws Exception {
        Field instances = Pf4jPluginHost.class.getDeclaredField("instances");
        instances.setAccessible(true);
        return Set.copyOf(((Map<UUID, ?>) instances.get(host)).keySet());
    }

    @SuppressWarnings("unchecked")
    private static BindingRuntimeResources activeResources(Pf4jPluginHost host, UUID bindingId) throws Exception {
        Field instances = Pf4jPluginHost.class.getDeclaredField("instances");
        instances.setAccessible(true);
        Object handle = ((Map<UUID, ?>) instances.get(host)).get(bindingId);
        var accessor = handle.getClass().getDeclaredMethod("resources");
        accessor.setAccessible(true);
        return (BindingRuntimeResources) accessor.invoke(handle);
    }

    private Pf4jPluginHost validationHost(String name) {
        return new Pf4jPluginHost(
                temporaryDirectory.resolve(name + "-plugins"),
                temporaryDirectory.resolve(name + "-plugin-data"),
                mock(PluginArtifactRepository.class),
                mock(BotRepository.class),
                mock(OutboxRepository.class),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    }

    private static void writeConfiguration(Pf4jPluginHost host, BotPluginBinding binding, String json)
            throws Exception {
        Files.createDirectories(host.bindingDataDirectory(binding));
        Files.writeString(host.configurationFile(binding), json);
    }
}
