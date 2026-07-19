package com.mieai.qqbot.plugin.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.PluginArtifact;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository;
import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginLogger;
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt;
import com.mieai.qqbot.plugin.api.MessageSender;
import com.mieai.qqbot.plugin.api.PluginStorage;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import com.mieai.qqbot.plugin.spi.PluginApiVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Trusted-JAR PF4J host with one isolated plugin instance per bot binding. */
public final class Pf4jPluginHost implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Pf4jPluginHost.class);
    private static final Set<String> SUPPORTED_CAPABILITIES =
            Set.of("event.read", "message.send", "storage");

    private final Path pluginDirectory;
    private final PluginArtifactRepository artifacts;
    private final BotRepository bots;
    private final OutboxRepository outbox;
    private final Optional<PluginStorageRepository> storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor pluginExecutor;
    private final PluginEventMapper eventMapper;
    private final Map<String, LoadedPlugin> loaded = new HashMap<>();
    private final Map<UUID, InstanceHandle> instances = new HashMap<>();
    private DefaultPluginManager manager;
    private boolean started;

    public Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, ObjectMapper mapper, Clock clock, Executor pluginExecutor) {
        this(pluginDirectory, artifacts, bots, outbox, Optional.empty(), mapper, clock, pluginExecutor);
    }

    public Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, ObjectMapper mapper,
            Clock clock, Executor pluginExecutor) {
        this(pluginDirectory, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")),
                mapper, clock, pluginExecutor);
    }

    private Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, Optional<PluginStorageRepository> storage, ObjectMapper mapper,
            Clock clock, Executor pluginExecutor) {
        this.pluginDirectory = Objects.requireNonNull(pluginDirectory, "pluginDirectory must not be null")
                .toAbsolutePath().normalize();
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.pluginExecutor = Objects.requireNonNull(pluginExecutor, "pluginExecutor must not be null");
        eventMapper = new PluginEventMapper(mapper);
    }

    public synchronized void start() {
        if (started) return;
        if (!Files.isDirectory(pluginDirectory)) {
            // A missing operator-mounted volume is a valid empty deployment; do not write outside
            // the configured data volume just to make the scanner happy.
            started = true;
            return;
        }
        DefaultPluginManager next = new DefaultPluginManager(pluginDirectory);
        next.setSystemVersion(PluginApiVersion.CURRENT);
        List<String> pluginIds = new ArrayList<>();
        try (var paths = Files.list(pluginDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String pluginId = next.loadPlugin(path);
                            if (pluginId != null) pluginIds.add(pluginId);
                        } catch (RuntimeException exception) {
                            LOGGER.warn("Plugin artifact {} could not be loaded ({})",
                                    path.getFileName(), exception.getClass().getSimpleName());
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list plugin directory", exception);
        }
        for (String pluginId : pluginIds) {
            try {
                next.startPlugin(pluginId);
            } catch (RuntimeException exception) {
                LOGGER.warn("Plugin {} could not be started ({})", pluginId, exception.getClass().getSimpleName());
            }
        }
        manager = next;
        loaded.clear();
        Instant now = clock.instant();
        for (PluginWrapper wrapper : next.getPlugins(PluginState.STARTED)) {
            if (!(wrapper.getPlugin() instanceof Pf4jPluginBridge bridge)) {
                LOGGER.warn("Plugin {} does not use the QQBot PF4J bridge", wrapper.getPluginId());
                continue;
            }
            try {
                BotPluginFactory factory = bridge.factory();
                if (!wrapper.getPluginId().equals(factory.pluginId())) {
                    throw new IllegalStateException("Plugin descriptor id does not match factory id");
                }
                LoadedPluginDetails details = metadata(wrapper, factory);
                loaded.put(details.metadata().id(), new LoadedPlugin(details.metadata(), factory, details.schema()));
                LoadedPluginMetadata metadata = details.metadata();
                Optional<PluginArtifact> previous = artifacts.findById(metadata.id());
                artifacts.upsert(new PluginArtifact(metadata.id(), metadata.name(), metadata.version(),
                        metadata.apiCompatibility(), metadata.path().getFileName().toString(), metadata.sha256(),
                        metadata.entrypoint(), "LOADED", true, previous.map(PluginArtifact::createdAt).orElse(now), now));
            } catch (RuntimeException exception) {
                LOGGER.warn("Plugin {} failed QQBot validation ({})", wrapper.getPluginId(),
                        exception.getClass().getSimpleName());
                next.stopPlugin(wrapper.getPluginId());
            }
        }
        started = true;
    }

    public synchronized void reload() {
        stopInternal();
        start();
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isLoaded(String pluginId) {
        return loaded.containsKey(pluginId);
    }

    public synchronized Set<String> loadedPluginIds() {
        return Set.copyOf(loaded.keySet());
    }

    public synchronized List<LoadedPluginMetadata> loadedPlugins() {
        return loaded.values().stream().map(LoadedPlugin::metadata)
                .sorted(Comparator.comparing(LoadedPluginMetadata::id)).toList();
    }

    /** Returns schema violations without exposing Jackson types to the admin module. */
    public synchronized List<String> validateConfiguration(String pluginId, String configurationJson) {
        LoadedPlugin plugin = loaded.get(pluginId);
        if (plugin == null) return List.of("Plugin is not loaded");
        try {
            JsonNode value = mapper.readTree(configurationJson);
            if (value == null || !value.isObject()) return List.of("$ must be an object");
            return new PluginConfigurationValidator().validate(plugin.schema(), value);
        } catch (IOException | RuntimeException exception) {
            return List.of("Configuration is not valid JSON");
        }
    }

    public CompletionStage<Void> execute(BotPluginBinding binding, InboxEvent inboxEvent) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(inboxEvent, "inboxEvent must not be null");
        InstanceHandle handle;
        synchronized (this) {
            handle = instance(binding);
        }
        PluginEvent event = eventMapper.map(inboxEvent);
        CompletableFuture<Void> result = new CompletableFuture<>();
        pluginExecutor.execute(() -> {
            try {
                CompletionStage<Void> stage = Objects.requireNonNull(
                        handle.plugin().onEvent(event), "plugin returned a null CompletionStage");
                stage.whenComplete((ignored, failure) -> {
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.minimalCompletionStage();
    }

    public synchronized void invalidate(UUID bindingId) {
        InstanceHandle handle = instances.remove(bindingId);
        if (handle != null) stop(handle);
    }

    public synchronized void invalidateAll() {
        List<InstanceHandle> handles = new ArrayList<>(instances.values());
        instances.clear();
        handles.forEach(this::stop);
    }

    private InstanceHandle instance(BotPluginBinding binding) {
        if (!started) throw new IllegalStateException("Plugin host is not started");
        InstanceHandle current = instances.get(binding.id());
        if (current != null && current.revision() == binding.revision()
                && current.pluginId().equals(binding.pluginId())) return current;
        if (current != null) stop(current);
        LoadedPlugin plugin = loaded.get(binding.pluginId());
        if (plugin == null) throw new IllegalStateException("Plugin is not loaded: " + binding.pluginId());
        var bot = bots.findById(binding.botId()).orElseThrow(
                () -> new IllegalStateException("Bot does not exist for plugin binding"));
        var environment = bot.definition().environment();
        PluginLogger logger = new BindingLogger(binding.pluginId(), binding.botId().toString());
        MessageSender sender = plugin.metadata().capabilities().contains("message.send")
                ? new DurableMessageSender(binding.id(), binding.botId(), environment, outbox, mapper, clock)
                : new DeniedMessageSender();
        PluginStorage pluginStorage = plugin.metadata().capabilities().contains("storage") && storage.isPresent()
                ? new DurablePluginStorage(binding.id(), storage.get(), clock)
                : PluginStorage.denied();
        PluginContext context = new PluginContext(binding.botId(), environment, binding.pluginId(),
                binding.configJson(), sender, logger, pluginStorage);
        BotPlugin instance = Objects.requireNonNull(plugin.factory().create(context), "plugin factory returned null");
        instance.start(context);
        InstanceHandle handle = new InstanceHandle(binding.id(), binding.pluginId(), binding.revision(), instance);
        instances.put(binding.id(), handle);
        return handle;
    }

    private LoadedPluginDetails metadata(PluginWrapper wrapper, BotPluginFactory factory) {
        Path path = wrapper.getPluginPath().toAbsolutePath().normalize();
        String name = wrapper.getPluginId();
        String api = wrapper.getDescriptor().getRequires();
        if (api == null || api.isBlank()) api = PluginApiVersion.CURRENT;
        String schemaPath = null;
        Set<String> capabilities = new HashSet<>();
        if (Files.isRegularFile(path)) {
            try (JarFile jar = new JarFile(path.toFile(), false)) {
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    String declared = manifest.getMainAttributes().getValue("Plugin-Name");
                    if (declared != null && !declared.isBlank()) name = declared.strip();
                    schemaPath = manifest.getMainAttributes().getValue("Plugin-Config-Schema");
                    String declaredCapabilities = manifest.getMainAttributes().getValue("Plugin-Capabilities");
                    if (declaredCapabilities != null) {
                        Arrays.stream(declaredCapabilities.split(","))
                                .map(String::strip).filter(value -> !value.isBlank()).forEach(capabilities::add);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to inspect loaded plugin", exception);
            }
        }
        if (schemaPath == null || schemaPath.isBlank()) {
            throw new IllegalStateException("Plugin manifest must declare Plugin-Config-Schema");
        }
        if (!capabilities.contains("event.read")) capabilities.add("event.read");
        for (String capability : capabilities) {
            if (!SUPPORTED_CAPABILITIES.contains(capability)) {
                throw new IllegalStateException("Unsupported plugin capability: " + capability);
            }
        }
        JsonNode schema;
        try (InputStream input = wrapper.getPluginClassLoader().getResourceAsStream(schemaPath)) {
            if (input == null) throw new IllegalStateException("Plugin configuration schema resource is missing");
            schema = mapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read plugin configuration schema", exception);
        }
        if (schema == null || !schema.isObject()) throw new IllegalStateException("Plugin configuration schema must be an object");
        LoadedPluginMetadata metadata = new LoadedPluginMetadata(wrapper.getPluginId(), name,
                wrapper.getDescriptor().getVersion(), api, path, sha256(path), factory.getClass().getName(),
                schemaPath, capabilities);
        return new LoadedPluginDetails(metadata, schema);
    }

    private static String sha256(Path path) {
        if (!Files.isRegularFile(path)) return "development-directory";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash plugin artifact", exception);
        }
    }

    @Override
    public synchronized void close() {
        stopInternal();
    }

    private void stopInternal() {
        invalidateAll();
        if (manager != null) {
            manager.stopPlugins();
            manager.unloadPlugins();
            manager = null;
        }
        loaded.clear();
        started = false;
    }

    private void stop(InstanceHandle handle) {
        try {
            handle.plugin().stop();
        } catch (RuntimeException exception) {
            LOGGER.warn("Plugin {} failed while stopping binding {}", handle.pluginId(), handle.bindingId());
        }
    }

    private record LoadedPlugin(LoadedPluginMetadata metadata, BotPluginFactory factory, JsonNode schema) {}
    private record LoadedPluginDetails(LoadedPluginMetadata metadata, JsonNode schema) {}
    private record InstanceHandle(UUID bindingId, String pluginId, long revision, BotPlugin plugin) {}

    private static final class BindingLogger implements PluginLogger {
        private final String prefix;
        private BindingLogger(String pluginId, String botId) { prefix = "plugin=" + pluginId + " bot=" + botId + " "; }
        @Override public void info(String message) { LOGGER.info("{}{}", prefix, sanitize(message)); }
        @Override public void warn(String message) { LOGGER.warn("{}{}", prefix, sanitize(message)); }
        @Override public void error(String message, Throwable cause) {
            LOGGER.error("{}{} ({})", prefix, sanitize(message), cause == null ? "unknown" : cause.getClass().getSimpleName());
        }
        private static String sanitize(String value) {
            if (value == null) return "";
            String clean = value.replace('\n', ' ').replace('\r', ' ');
            return clean.length() > 512 ? clean.substring(0, 512) : clean;
        }
    }

    private static final class DeniedMessageSender implements MessageSender {
        @Override
        public java.util.concurrent.CompletionStage<MessageEnqueueReceipt> enqueue(TextMessage message) {
            return CompletableFuture.failedFuture(new SecurityException("Plugin does not have message.send capability"));
        }
    }

    private static final class DurablePluginStorage implements PluginStorage {
        private final UUID bindingId;
        private final PluginStorageRepository repository;
        private final Clock clock;

        private DurablePluginStorage(UUID bindingId, PluginStorageRepository repository, Clock clock) {
            this.bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
            this.repository = Objects.requireNonNull(repository, "repository must not be null");
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
        }

        @Override
        public Optional<String> get(String namespace, String key) {
            return repository.find(bindingId, namespace, key);
        }

        @Override
        public void put(String namespace, String key, String value) {
            repository.put(bindingId, namespace, key, value, clock.instant());
        }

        @Override
        public void delete(String namespace, String key) {
            repository.delete(bindingId, namespace, key);
        }

        @Override
        public Map<String, String> list(String namespace) {
            return repository.list(bindingId, namespace);
        }
    }
}
