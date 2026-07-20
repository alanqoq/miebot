package com.mieai.qqbot.plugin.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.PluginArtifact;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.plugin.api.ConfigSnapshot;
import com.mieai.qqbot.plugin.api.EventService;
import com.mieai.qqbot.plugin.api.MediaService;
import com.mieai.qqbot.plugin.api.MessageEnqueueReceipt;
import com.mieai.qqbot.plugin.api.MessageSender;
import com.mieai.qqbot.plugin.api.PluginContext;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginLogger;
import com.mieai.qqbot.plugin.api.PluginRuntimeContext;
import com.mieai.qqbot.plugin.api.PluginScheduler;
import com.mieai.qqbot.plugin.api.PluginStorage;
import com.mieai.qqbot.plugin.api.RestrictedHttpClient;
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import com.mieai.qqbot.plugin.spi.BotPluginFactoryV2;
import com.mieai.qqbot.plugin.spi.PluginApiVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

/** Trusted-JAR PF4J host with binding-scoped resources and atomic in-process upgrades. */
public final class Pf4jPluginHost implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Pf4jPluginHost.class);
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
            "event.read", "event.subscribe", "message.send", "media.send", "storage", "scheduler", "http");
    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);

    private final Path pluginDirectory;
    private final PluginArtifactRepository artifacts;
    private final BotRepository bots;
    private final OutboxRepository outbox;
    private final Optional<PluginStorageRepository> storage;
    private final Optional<MediaAssetStore> mediaStore;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int queueCapacity;
    private final Duration shutdownTimeout;
    private final PluginEventMapper eventMapper;
    private final Map<String, LoadedPlugin> loaded = new HashMap<>();
    private final Map<UUID, InstanceHandle> instances = new HashMap<>();
    private DefaultPluginManager manager;
    private boolean started;

    public Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, ObjectMapper mapper, Clock clock, Executor ignoredExecutor) {
        this(pluginDirectory, artifacts, bots, outbox, Optional.empty(), mapper, clock,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_TIMEOUT, Optional.empty());
    }

    public Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, ObjectMapper mapper,
            Clock clock, Executor ignoredExecutor) {
        this(pluginDirectory, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")), mapper, clock,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_TIMEOUT, Optional.empty());
    }

    public Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, ObjectMapper mapper,
            Clock clock, int queueCapacity, Duration shutdownTimeout) {
        this(pluginDirectory, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")), mapper, clock,
                queueCapacity, shutdownTimeout, Optional.empty());
    }

    public Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, MediaAssetStore mediaStore,
            ObjectMapper mapper, Clock clock, int queueCapacity, Duration shutdownTimeout) {
        this(pluginDirectory, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")), mapper, clock,
                queueCapacity, shutdownTimeout,
                Optional.of(Objects.requireNonNull(mediaStore, "mediaStore must not be null")));
    }

    private Pf4jPluginHost(Path pluginDirectory, PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, Optional<PluginStorageRepository> storage, ObjectMapper mapper,
            Clock clock, int queueCapacity, Duration shutdownTimeout,
            Optional<MediaAssetStore> mediaStore) {
        this.pluginDirectory = Objects.requireNonNull(pluginDirectory, "pluginDirectory must not be null")
                .toAbsolutePath().normalize();
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (queueCapacity < 1 || queueCapacity > 100_000) throw new IllegalArgumentException("queueCapacity is invalid");
        this.queueCapacity = queueCapacity;
        this.shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
        eventMapper = new PluginEventMapper(mapper);
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        if (!Files.isDirectory(pluginDirectory)) return;
        manager = newManager(pluginDirectory);
        List<Path> jars;
        try (var paths = Files.list(pluginDirectory)) {
            jars = paths.filter(Files::isRegularFile)
                    .filter(Pf4jPluginHost::isJar)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list plugin directory", exception);
        }
        for (Path path : jars) {
            try {
                loadIntoHost(path, false);
            } catch (RuntimeException exception) {
                LOGGER.warn("Plugin artifact {} could not be loaded ({})",
                        path.getFileName(), exception.getClass().getSimpleName());
            }
        }
    }

    public synchronized void reload() {
        stopInternal();
        start();
    }

    public PluginArtifactCandidate validateArtifact(Path candidate) {
        Path path = normalizedRegularJar(candidate);
        DefaultPluginManager verifier = newManager(path.getParent());
        String pluginId = null;
        try {
            pluginId = verifier.loadPlugin(path);
            if (pluginId == null) throw new IllegalStateException("PF4J did not return a plugin id");
            verifier.startPlugin(pluginId);
            LoadedPluginDetails details = details(verifier.getPlugin(pluginId));
            LoadedPluginMetadata metadata = details.metadata();
            return new PluginArtifactCandidate(metadata.id(), metadata.name(), metadata.version(),
                    metadata.apiCompatibility(), path.getFileName().toString(), metadata.sha256(), path);
        } finally {
            if (pluginId != null) {
                try { verifier.stopPlugin(pluginId); } catch (RuntimeException ignored) {}
                try { verifier.unloadPlugin(pluginId); } catch (RuntimeException ignored) {}
            }
            verifier.unloadPlugins();
        }
    }

    public synchronized PluginArtifactInstallResult installArtifact(Path stagedArtifact) {
        if (!started) throw new IllegalStateException("Plugin host is not started");
        PluginArtifactCandidate candidate = validateArtifact(stagedArtifact);
        LoadedPlugin previous = loaded.get(candidate.pluginId());
        if (previous != null && previous.metadata().sha256().equals(candidate.sha256())) {
            deleteQuietly(candidate.path());
            return new PluginArtifactInstallResult("UNCHANGED", previous.metadata(),
                    Optional.of(previous.metadata().version()), Optional.of(previous.metadata().sha256()));
        }

        Path previousPath = previous == null ? null : previous.metadata().path();
        List<InstanceHandle> stoppedInstances = removeInstances(candidate.pluginId());
        stopInstances(stoppedInstances);

        Path target = pluginDirectory.resolve(artifactFileName(candidate)).normalize();
        if (!target.getParent().equals(pluginDirectory)) throw new IllegalStateException("Invalid plugin target path");
        boolean targetCreated = false;
        try {
            if (previous != null) unload(candidate.pluginId());
            Files.createDirectories(pluginDirectory);
            if (Files.exists(target)) {
                if (!Files.isRegularFile(target) || !candidate.sha256().equals(sha256(target))) {
                    throw new IllegalStateException("Plugin target already exists with different content");
                }
                deleteQuietly(candidate.path());
            } else {
                move(candidate.path(), target);
                targetCreated = true;
            }
            LoadedPlugin installed = loadIntoHost(target, true);
            cleanupSupersededArtifacts(candidate.pluginId(), target, previousPath);
            return new PluginArtifactInstallResult(previous == null ? "INSTALLED" : "UPGRADED",
                    installed.metadata(), previous == null ? Optional.empty() : Optional.of(previous.metadata().version()),
                    previous == null ? Optional.empty() : Optional.of(previous.metadata().sha256()));
        } catch (IOException | RuntimeException failure) {
            if (targetCreated) deleteQuietly(target);
            if (previousPath != null && Files.isRegularFile(previousPath)
                    && !loaded.containsKey(candidate.pluginId())) {
                try {
                    loadIntoHost(previousPath, true);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new IllegalStateException("Unable to install plugin artifact", failure);
        }
    }

    public synchronized boolean isStarted() { return started; }
    public synchronized boolean isLoaded(String pluginId) { return loaded.containsKey(pluginId); }
    public synchronized boolean isLoaded(String pluginId, String sha256) {
        LoadedPlugin plugin = loaded.get(pluginId);
        return plugin != null && plugin.metadata().sha256().equals(sha256);
    }
    public synchronized Set<String> loadedPluginIds() { return Set.copyOf(loaded.keySet()); }

    /** Hashes currently loaded by this JVM, used for multi-instance lease admission. */
    public synchronized Map<String, String> loadedPluginHashes() {
        return loaded.values().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                value -> value.metadata().id(), value -> value.metadata().sha256()));
    }

    public synchronized List<LoadedPluginMetadata> loadedPlugins() {
        return loaded.values().stream().map(LoadedPlugin::metadata)
                .sorted(Comparator.comparing(LoadedPluginMetadata::id)).toList();
    }

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

    public synchronized List<String> handlerIds(BotPluginBinding binding) {
        InstanceHandle handle = instance(binding);
        return handle.resources().handlerIds(handle.plugin().handlerId());
    }

    public synchronized List<String> handlerIds(BotPluginBinding binding, String eventType) {
        InstanceHandle handle = instance(binding);
        return handle.resources().handlerIds(handle.plugin().handlerId(), eventType);
    }

    public CompletionStage<Void> execute(BotPluginBinding binding, InboxEvent inboxEvent) {
        String handlerId;
        synchronized (this) {
            InstanceHandle handle = instance(binding);
            handlerId = handle.resources().handlerIds(handle.plugin().handlerId()).getFirst();
        }
        return execute(binding, inboxEvent, handlerId);
    }

    public CompletionStage<Void> execute(BotPluginBinding binding, InboxEvent inboxEvent, String handlerId) {
        return executeCancellable(binding, inboxEvent, handlerId).stage();
    }

    PluginExecution executeCancellable(
            BotPluginBinding binding, InboxEvent inboxEvent, String handlerId) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(inboxEvent, "inboxEvent must not be null");
        InstanceHandle handle;
        synchronized (this) { handle = instance(binding); }
        PluginEvent event = eventMapper.map(inboxEvent);
        return handle.resources().executeCancellable(handlerId, event, handle.plugin()::onEvent);
    }

    public synchronized void invalidate(UUID bindingId) {
        InstanceHandle handle = instances.remove(bindingId);
        if (handle != null) stop(handle);
    }

    /** Immediately fences a timed-out binding before its durable state is quarantined. */
    synchronized void quarantine(UUID bindingId) {
        InstanceHandle handle = instances.remove(bindingId);
        if (handle == null) return;
        handle.resources().beginShutdown();
        handle.resources().close();
        if (handle.httpClient() != null) handle.httpClient().close();
        try { handle.plugin().stop(); }
        catch (RuntimeException exception) {
            LOGGER.warn("Plugin {} failed while quarantining binding {}",
                    handle.pluginId(), handle.bindingId());
        }
    }

    public synchronized void invalidateAll() {
        List<InstanceHandle> handles = new ArrayList<>(instances.values());
        instances.clear();
        stopInstances(handles);
    }

    private InstanceHandle instance(BotPluginBinding binding) {
        if (!started) throw new IllegalStateException("Plugin host is not started");
        InstanceHandle current = instances.get(binding.id());
        if (current != null && current.revision() == binding.revision()
                && current.pluginId().equals(binding.pluginId())) return current;
        if (current != null) { instances.remove(binding.id()); stop(current); }
        LoadedPlugin plugin = loaded.get(binding.pluginId());
        if (plugin == null) throw new IllegalStateException("Plugin is not loaded: " + binding.pluginId());
        var bot = bots.findById(binding.botId()).orElseThrow(
                () -> new IllegalStateException("Bot does not exist for plugin binding"));
        var environment = bot.definition().environment();
        PluginLogger logger = new BindingLogger(binding.pluginId(), binding.botId().toString());
        BindingRuntimeResources resources = new BindingRuntimeResources(
                binding.pluginId(), binding.id().toString(), queueCapacity);
        DurableMessageSender durable = new DurableMessageSender(binding.id(), binding.botId(), environment,
                outbox, mapper, clock, resources.capabilityGuard(), mediaStore.orElse(null),
                bot.definition().maxMediaUploadBytes());
        MessageSender sender = plugin.metadata().capabilities().contains("message.send") ? durable : new DeniedMessageSender();
        PluginStorage pluginStorage = plugin.metadata().capabilities().contains("storage") && storage.isPresent()
                ? new DurablePluginStorage(binding.id(), storage.orElseThrow(), clock) : PluginStorage.denied();
        RestrictedPluginHttpClient http = plugin.metadata().capabilities().contains("http")
                ? new RestrictedPluginHttpClient() : null;
        PluginContext base = new PluginContext(binding.botId(), environment, binding.pluginId(),
                binding.configJson(), sender, logger, pluginStorage);
        PluginRuntimeContext extended = new PluginRuntimeContext(base,
                new ConfigSnapshot(binding.configJson(), binding.revision(), clock.instant()),
                plugin.metadata().capabilities().contains("event.subscribe")
                        ? resources.eventService() : EventService.denied(),
                plugin.metadata().capabilities().contains("scheduler")
                        ? resources.pluginScheduler() : PluginScheduler.denied(),
                http == null ? RestrictedHttpClient.denied() : http,
                plugin.metadata().capabilities().contains("media.send")
                        ? durable : MediaService.denied());
        try {
            BotPlugin created = plugin.factory() instanceof BotPluginFactoryV2 v2
                    ? v2.create(extended) : plugin.factory().create(base);
            BotPlugin botPlugin = Objects.requireNonNull(created, "plugin factory returned null");
            if (plugin.factory() instanceof BotPluginFactoryV2) botPlugin.start(extended);
            else botPlugin.start(base);
            InstanceHandle handle = new InstanceHandle(binding.id(), binding.pluginId(), binding.revision(),
                    botPlugin, resources, http);
            instances.put(binding.id(), handle);
            return handle;
        } catch (RuntimeException failure) {
            resources.close();
            if (http != null) http.close();
            throw failure;
        }
    }

    private LoadedPlugin loadIntoHost(Path path, boolean authoritative) {
        ensureManager();
        String pluginId = manager.loadPlugin(path);
        if (pluginId == null) throw new IllegalStateException("PF4J did not return a plugin id");
        try {
            manager.startPlugin(pluginId);
            LoadedPluginDetails details = details(manager.getPlugin(pluginId));
            LoadedPlugin plugin = new LoadedPlugin(details.metadata(), details.factory(), details.schema());
            loaded.put(pluginId, plugin);
            persist(plugin.metadata(), authoritative);
            return plugin;
        } catch (RuntimeException exception) {
            try { manager.stopPlugin(pluginId); } catch (RuntimeException ignored) {}
            try { manager.unloadPlugin(pluginId); } catch (RuntimeException ignored) {}
            loaded.remove(pluginId);
            throw exception;
        }
    }

    private LoadedPluginDetails details(PluginWrapper wrapper) {
        if (wrapper == null || !(wrapper.getPlugin() instanceof Pf4jPluginBridge bridge)) {
            throw new IllegalStateException("Plugin does not use the QQBot PF4J bridge");
        }
        BotPluginFactory factory = bridge.factory();
        if (!wrapper.getPluginId().equals(factory.pluginId())) {
            throw new IllegalStateException("Plugin descriptor id does not match factory id");
        }
        Path path = wrapper.getPluginPath().toAbsolutePath().normalize();
        String name = wrapper.getPluginId();
        String api = wrapper.getDescriptor().getRequires();
        if (api == null || api.isBlank()) api = PluginApiVersion.CURRENT;
        String schemaPath = null;
        Set<String> capabilities = new HashSet<>();
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
        if (schemaPath == null || schemaPath.isBlank()) {
            throw new IllegalStateException("Plugin manifest must declare Plugin-Config-Schema");
        }
        capabilities.add("event.read");
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
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException("Plugin configuration schema must be an object");
        }
        LoadedPluginMetadata metadata = new LoadedPluginMetadata(wrapper.getPluginId(), name,
                wrapper.getDescriptor().getVersion(), api, path, sha256(path), factory.getClass().getName(),
                schemaPath, capabilities);
        return new LoadedPluginDetails(metadata, factory, schema);
    }

    private void persist(LoadedPluginMetadata metadata, boolean authoritative) {
        Instant now = clock.instant();
        Optional<PluginArtifact> previous = artifacts.findById(metadata.id());
        if (!authoritative && previous.isPresent()
                && !previous.orElseThrow().sha256().equals(metadata.sha256())) {
            LOGGER.warn("Plugin {} hash {} differs from catalog hash {}; this instance will not own matching bots",
                    metadata.id(), metadata.sha256(), previous.orElseThrow().sha256());
            return;
        }
        artifacts.upsert(new PluginArtifact(metadata.id(), metadata.name(), metadata.version(),
                metadata.apiCompatibility(), metadata.path().getFileName().toString(), metadata.sha256(),
                metadata.entrypoint(), "LOADED", true, previous.map(PluginArtifact::createdAt).orElse(now), now));
    }

    private void unload(String pluginId) {
        removeInstances(pluginId).forEach(this::stop);
        if (manager != null && manager.getPlugin(pluginId) != null) {
            manager.stopPlugin(pluginId);
            if (!manager.unloadPlugin(pluginId)) {
                try { manager.startPlugin(pluginId); }
                catch (RuntimeException restartFailure) {
                    loaded.remove(pluginId);
                    throw new IllegalStateException("Unable to unload or restore plugin " + pluginId, restartFailure);
                }
                throw new IllegalStateException("Unable to unload plugin " + pluginId);
            }
        }
        loaded.remove(pluginId);
    }

    private List<InstanceHandle> removeInstances(String pluginId) {
        List<InstanceHandle> removed = instances.values().stream()
                .filter(value -> value.pluginId().equals(pluginId)).toList();
        removed.forEach(value -> instances.remove(value.bindingId()));
        return removed;
    }

    private void stopInstances(List<InstanceHandle> handles) { handles.forEach(this::stop); }

    private void stop(InstanceHandle handle) {
        handle.resources().beginShutdown();
        if (!handle.resources().awaitIdle(shutdownTimeout)) {
            LOGGER.warn("Plugin {} binding {} did not become idle before shutdown",
                    handle.pluginId(), handle.bindingId());
        }
        try { handle.plugin().stop(); }
        catch (RuntimeException exception) {
            LOGGER.warn("Plugin {} failed while stopping binding {}", handle.pluginId(), handle.bindingId());
        } finally {
            handle.resources().close();
            if (handle.httpClient() != null) handle.httpClient().close();
        }
    }

    @Override
    public synchronized void close() { stopInternal(); }

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

    private void ensureManager() {
        if (manager == null) manager = newManager(pluginDirectory);
    }

    private static DefaultPluginManager newManager(Path root) {
        DefaultPluginManager value = new DefaultPluginManager(root);
        value.setSystemVersion(PluginApiVersion.CURRENT);
        return value;
    }

    private void cleanupSupersededArtifacts(String pluginId, Path target, Path previousPath) {
        if (previousPath != null && !previousPath.equals(target)) deleteQuietly(previousPath);
        try (var paths = Files.list(pluginDirectory)) {
            paths.filter(Files::isRegularFile).filter(Pf4jPluginHost::isJar)
                    .filter(path -> !path.equals(target))
                    .filter(path -> pluginId.equals(manifestPluginId(path)))
                    .forEach(Pf4jPluginHost::deleteQuietly);
        } catch (IOException exception) {
            LOGGER.warn("Unable to clean superseded artifacts for {}", pluginId);
        }
    }

    private static String manifestPluginId(Path path) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Plugin-Id");
        } catch (IOException exception) { return null; }
    }

    private static Path normalizedRegularJar(Path value) {
        Path path = Objects.requireNonNull(value, "candidate must not be null").toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !isJar(path)) throw new IllegalArgumentException("candidate must be a JAR file");
        return path;
    }

    private static boolean isJar(Path path) {
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static String artifactFileName(PluginArtifactCandidate candidate) {
        return safe(candidate.pluginId()) + "-" + safe(candidate.version()) + "-"
                + candidate.sha256().substring(0, 12) + ".jar";
    }

    private static String safe(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isBlank() ? "plugin" : safe;
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException exception) { LOGGER.warn("Unable to delete plugin artifact {}", path.getFileName()); }
    }

    private static String sha256(Path path) {
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

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private record LoadedPlugin(LoadedPluginMetadata metadata, BotPluginFactory factory, JsonNode schema) {}
    private record LoadedPluginDetails(LoadedPluginMetadata metadata, BotPluginFactory factory, JsonNode schema) {}
    private record InstanceHandle(UUID bindingId, String pluginId, long revision, BotPlugin plugin,
            BindingRuntimeResources resources, RestrictedPluginHttpClient httpClient) {}

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
        @Override public CompletionStage<MessageEnqueueReceipt> enqueue(TextMessage message) {
            return CompletableFuture.failedFuture(new SecurityException("Plugin does not have message.send capability"));
        }
    }

    private static final class DurablePluginStorage implements PluginStorage {
        private final UUID bindingId;
        private final PluginStorageRepository repository;
        private final Clock clock;
        private DurablePluginStorage(UUID bindingId, PluginStorageRepository repository, Clock clock) {
            this.bindingId = bindingId; this.repository = repository; this.clock = clock;
        }
        @Override public Optional<String> get(String namespace, String key) { return repository.find(bindingId, namespace, key); }
        @Override public void put(String namespace, String key, String value) { repository.put(bindingId, namespace, key, value, clock.instant()); }
        @Override public void delete(String namespace, String key) { repository.delete(bindingId, namespace, key); }
        @Override public Map<String, String> list(String namespace) { return repository.list(bindingId, namespace); }
    }
}
