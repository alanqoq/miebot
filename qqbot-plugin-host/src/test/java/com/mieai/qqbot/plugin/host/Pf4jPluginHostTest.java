package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.JdbcBotRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository;
import com.mieai.qqbot.persistence.inbox.IncomingEvent;
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxQuery;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginStorageRepository;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer;
import com.mieai.qqbot.runtime.outbox.OutboundTextPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Enumeration;
import java.util.UUID;
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

        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, artifacts,
                new JdbcBotRepository(dataSource), outbox, storage, new ObjectMapper(), clock, Runnable::run)) {
            host.start();
            assertThat(host.isLoaded("echo")).isTrue();
            assertThat(artifacts.findById("echo")).isPresent();

            BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), "echo", BotId.parse(BOT),
                    "{}", true, 0, NOW, NOW);
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
                    BotId.parse("550e8400-e29b-41d4-a716-446655440002"), "{}", true, 0, NOW, NOW);
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
        }
    }

    @Test
    void upgradesPluginWithoutRestartAndRestoresPreviousArtifactWhenInstallationFails() throws Exception {
        Path pluginDirectory = temporaryDirectory.resolve("hot-plugins");
        Path stagingDirectory = pluginDirectory.resolve(".staging");
        Files.createDirectories(stagingDirectory);
        Path example = Path.of(System.getProperty("qqbot.example.plugin"));
        Path oldArtifact = pluginWithVersion(example, pluginDirectory.resolve("echo-old.jar"), "1.0.0");

        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("hot-host.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        insertBot(dataSource);
        var artifacts = new JdbcPluginArtifactRepository(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        try (Pf4jPluginHost host = new Pf4jPluginHost(pluginDirectory, artifacts,
                new JdbcBotRepository(dataSource), new JdbcOutboxRepository(dataSource),
                new JdbcPluginStorageRepository(dataSource), new ObjectMapper(), clock, Runnable::run)) {
            host.start();
            assertThat(host.loadedPlugins()).singleElement()
                    .extracting(LoadedPluginMetadata::version).isEqualTo("1.0.0");

            BotPluginBinding binding = new BotPluginBinding(UUID.randomUUID(), "echo", BotId.parse(BOT),
                    "{}", true, 0, NOW, NOW);
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
}
