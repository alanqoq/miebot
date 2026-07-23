package com.mieai.qqbot.plugin.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotId;
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
import com.mieai.qqbot.plugin.api.TextMessage;
import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.spi.BotPluginFactory;
import com.mieai.qqbot.plugin.spi.PluginApiVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
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
    private static final String CONFIGURATION_FILE_NAME = "config.json";
    private static final int MAX_CONFIGURATION_BYTES = 64 * 1024;
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile(
            "[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?");
    private static final Set<String> WINDOWS_RESERVED_PLUGIN_ID_STEMS = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final Path pluginDirectory;
    private final Path pluginDataRoot;
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

    public Pf4jPluginHost(Path pluginDirectory, Path pluginDataRoot,
            PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, ObjectMapper mapper, Clock clock, Executor ignoredExecutor) {
        this(pluginDirectory, pluginDataRoot, artifacts, bots, outbox, Optional.empty(), mapper, clock,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_TIMEOUT, Optional.empty());
    }

    public Pf4jPluginHost(Path pluginDirectory, Path pluginDataRoot,
            PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, ObjectMapper mapper,
            Clock clock, Executor ignoredExecutor) {
        this(pluginDirectory, pluginDataRoot, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")), mapper, clock,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_SHUTDOWN_TIMEOUT, Optional.empty());
    }

    public Pf4jPluginHost(Path pluginDirectory, Path pluginDataRoot,
            PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, ObjectMapper mapper,
            Clock clock, int queueCapacity, Duration shutdownTimeout) {
        this(pluginDirectory, pluginDataRoot, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")), mapper, clock,
                queueCapacity, shutdownTimeout, Optional.empty());
    }

    public Pf4jPluginHost(Path pluginDirectory, Path pluginDataRoot,
            PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, PluginStorageRepository storage, MediaAssetStore mediaStore,
            ObjectMapper mapper, Clock clock, int queueCapacity, Duration shutdownTimeout) {
        this(pluginDirectory, pluginDataRoot, artifacts, bots, outbox,
                Optional.of(Objects.requireNonNull(storage, "storage must not be null")), mapper, clock,
                queueCapacity, shutdownTimeout,
                Optional.of(Objects.requireNonNull(mediaStore, "mediaStore must not be null")));
    }

    private Pf4jPluginHost(Path pluginDirectory, Path pluginDataRoot,
            PluginArtifactRepository artifacts, BotRepository bots,
            OutboxRepository outbox, Optional<PluginStorageRepository> storage, ObjectMapper mapper,
            Clock clock, int queueCapacity, Duration shutdownTimeout,
            Optional<MediaAssetStore> mediaStore) {
        this.pluginDirectory = Objects.requireNonNull(pluginDirectory, "pluginDirectory must not be null")
                .toAbsolutePath().normalize();
        this.pluginDataRoot = Objects.requireNonNull(pluginDataRoot, "pluginDataRoot must not be null")
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
            requirePluginId(pluginId);
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

    /** Returns the normalized JSON object declared by the plugin artifact as its binding default. */
    public synchronized String defaultConfiguration(String pluginId) {
        LoadedPlugin plugin = loaded.get(requirePluginId(pluginId));
        if (plugin == null) throw new IllegalStateException("Plugin is not loaded: " + pluginId);
        return plugin.metadata().defaultConfiguration();
    }

    /** Resolves the private data directory owned by exactly one bot/plugin binding. */
    public Path bindingDataDirectory(BotPluginBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        Path botDirectory = pluginDataRoot.resolve(binding.botId().toString()).normalize();
        Path bindingDirectory = botDirectory.resolve(requirePluginId(binding.pluginId())).normalize();
        if (!bindingDirectory.startsWith(pluginDataRoot)
                || !Objects.equals(bindingDirectory.getParent(), botDirectory)) {
            throw new IllegalArgumentException("Plugin id does not resolve to one binding directory");
        }
        return bindingDirectory;
    }

    /** Returns the configured boundary containing every bot/plugin binding data directory. */
    public Path pluginDataRoot() {
        return pluginDataRoot;
    }

    /** Resolves the configuration file stored inside one bot/plugin binding directory. */
    public Path configurationFile(BotPluginBinding binding) {
        Path directory = bindingDataDirectory(binding);
        Path configuration = directory.resolve(CONFIGURATION_FILE_NAME).normalize();
        if (!Objects.equals(configuration.getParent(), directory)) {
            throw new IllegalStateException("Plugin configuration path escaped its binding directory");
        }
        return configuration;
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
        return handle.resources().handlerIds();
    }

    public synchronized List<String> handlerIds(BotPluginBinding binding, String eventType) {
        InstanceHandle handle = instance(binding);
        return handle.resources().handlerIds(eventType);
    }

    /**
     * Stops local instances whose authoritative binding no longer permits the same runtime.
     * Bindings without a local instance are deliberately left untouched and are materialized lazily.
     */
    public synchronized void reconcileBindings(List<BotPluginBinding> currentBindings) {
        Objects.requireNonNull(currentBindings, "currentBindings must not be null");
        Map<UUID, BotPluginBinding> authoritative = new HashMap<>();
        for (BotPluginBinding binding : currentBindings) {
            BotPluginBinding value = Objects.requireNonNull(binding, "currentBindings must not contain null");
            if (authoritative.put(value.id(), value) != null) {
                throw new IllegalArgumentException("currentBindings contains a duplicate binding id: " + value.id());
            }
        }

        List<InstanceHandle> stale = instances.values().stream()
                .filter(handle -> {
                    BotPluginBinding binding = authoritative.get(handle.bindingId());
                    return binding == null
                            || !binding.enabled()
                            || !binding.runtimeState().runnable()
                            || !handle.pluginId().equals(binding.pluginId())
                            || handle.revision() != binding.revision();
                })
                .toList();
        stale.forEach(handle -> instances.remove(handle.bindingId()));
        stopInstances(stale);
    }

    public CompletionStage<Void> execute(BotPluginBinding binding, InboxEvent inboxEvent) {
        String handlerId;
        synchronized (this) {
            InstanceHandle handle = instance(binding);
            List<String> handlerIds = handle.resources().handlerIds();
            if (handlerIds.isEmpty()) {
                throw new IllegalStateException("Plugin has no registered event handlers: " + binding.pluginId());
            }
            handlerId = handlerIds.getFirst();
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
        return handle.resources().executeCancellable(handlerId, event);
    }

    public synchronized void invalidate(UUID bindingId) {
        InstanceHandle handle = instances.remove(bindingId);
        if (handle != null) stop(handle);
    }

    /**
     * Fences one binding and requires every accepted callback to become idle before returning.
     *
     * <p>On timeout the handle remains registered but fenced, so no replacement instance can be
     * materialized until the caller retries or explicitly applies another lifecycle transition.
     */
    public synchronized void quiesceBindingStrict(UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        InstanceHandle handle = instances.get(bindingId);
        if (handle == null) return;
        handle.resources().beginShutdown();
        if (!handle.resources().awaitIdle(shutdownTimeout)) {
            LOGGER.warn("Plugin {} binding {} did not become idle before strict quiescence",
                    handle.pluginId(), handle.bindingId());
            throw new IllegalStateException("Plugin callbacks are still running for binding " + bindingId);
        }
        instances.remove(bindingId, handle);
        finishStop(handle);
    }

    /**
     * Fences every local binding for one bot and waits for all accepted callbacks to finish.
     *
     * <p>If the bounded wait expires, the instances remain fenced and registered so a later
     * deletion attempt can continue waiting. Callers must not delete the bot data directory when
     * this method throws.
     */
    public synchronized void invalidateBot(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        List<InstanceHandle> handles = instances.values().stream()
                .filter(handle -> handle.botId().equals(botId))
                .toList();
        handles.forEach(handle -> handle.resources().beginShutdown());

        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        List<InstanceHandle> busy = new ArrayList<>();
        for (InstanceHandle handle : handles) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            if (!handle.resources().awaitIdle(Duration.ofNanos(remaining))) {
                busy.add(handle);
            }
        }
        if (!busy.isEmpty()) {
            LOGGER.warn("{} plugin binding(s) for bot {} did not become idle before deletion",
                    busy.size(), botId);
            throw new IllegalStateException("Plugin callbacks are still running for bot " + botId);
        }

        handles.forEach(handle -> instances.remove(handle.bindingId(), handle));
        handles.forEach(this::finishStop);
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
        Path dataDirectory = requireBindingDataDirectory(binding);
        String configurationJson = readBindingConfiguration(binding, plugin, dataDirectory);
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
        RestrictedPluginHttpClient http = new RestrictedPluginHttpClient();
        PluginContext base = new PluginContext(binding.botId(), environment, binding.pluginId(), dataDirectory,
                configurationJson, sender, logger, pluginStorage);
        PluginRuntimeContext extended = new PluginRuntimeContext(base,
                new ConfigSnapshot(configurationJson, binding.revision(), clock.instant()),
                plugin.metadata().capabilities().contains("event.subscribe")
                        ? resources.eventService() : EventService.denied(),
                plugin.metadata().capabilities().contains("scheduler")
                        ? resources.pluginScheduler() : PluginScheduler.denied(),
                http,
                plugin.metadata().capabilities().contains("media.send")
                        ? durable : MediaService.denied());
        try {
            BotPlugin created = plugin.factory().create(extended);
            BotPlugin botPlugin = Objects.requireNonNull(created, "plugin factory returned null");
            botPlugin.start();
            InstanceHandle handle = new InstanceHandle(binding.id(), binding.botId(), binding.pluginId(), binding.revision(),
                    botPlugin, resources, http);
            instances.put(binding.id(), handle);
            return handle;
        } catch (RuntimeException failure) {
            resources.close();
            if (http != null) http.close();
            throw failure;
        }
    }

    private Path requireBindingDataDirectory(BotPluginBinding binding) {
        Path directory = bindingDataDirectory(binding);
        if (!Files.isDirectory(pluginDataRoot)) {
            throw new IllegalStateException("Plugin data root does not exist or is not a directory: "
                    + pluginDataRoot);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Plugin binding data directory does not exist or is not a directory: "
                    + directory);
        }
        try {
            Path realRoot = pluginDataRoot.toRealPath();
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(realRoot)) {
                throw new IllegalStateException("Plugin binding data directory escapes the configured data root: "
                        + directory);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve plugin binding data directory: " + directory,
                    exception);
        }
        return directory;
    }

    private String readBindingConfiguration(
            BotPluginBinding binding, LoadedPlugin plugin, Path dataDirectory) {
        Path configuration = configurationFile(binding);
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Plugin binding configuration file does not exist or is not a regular file: "
                    + configuration);
        }
        try {
            Path realDirectory = dataDirectory.toRealPath();
            Path realConfiguration = configuration.toRealPath();
            if (!Objects.equals(realConfiguration.getParent(), realDirectory)) {
                throw new IllegalStateException("Plugin binding configuration file escapes its data directory: "
                        + configuration);
            }
            String json = readBindingConfigurationUtf8(configuration);
            JsonNode value;
            try {
                value = mapper.readTree(json);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Plugin binding configuration is not valid JSON: "
                        + configuration, exception);
            }
            if (value == null || !value.isObject()) {
                throw new IllegalStateException("Plugin binding configuration must be a JSON object: "
                        + configuration);
            }
            List<String> errors = new PluginConfigurationValidator().validate(plugin.schema(), value);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Plugin binding configuration failed schema validation: "
                        + String.join("; ", errors));
            }
            return json;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read plugin binding configuration: " + configuration,
                    exception);
        }
    }

    private static String readBindingConfigurationUtf8(Path configuration) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(configuration)) {
            bytes = input.readNBytes(MAX_CONFIGURATION_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIGURATION_BYTES) {
            throw new IllegalStateException("Plugin binding configuration cannot exceed 64 KiB: "
                    + configuration);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Plugin binding configuration must be valid UTF-8: "
                    + configuration, exception);
        }
    }

    private LoadedPlugin loadIntoHost(Path path, boolean authoritative) {
        ensureManager();
        String pluginId = manager.loadPlugin(path);
        try {
            requirePluginId(pluginId);
            manager.startPlugin(pluginId);
            LoadedPluginDetails details = details(manager.getPlugin(pluginId));
            LoadedPlugin plugin = new LoadedPlugin(details.metadata(), details.factory(), details.schema());
            loaded.put(pluginId, plugin);
            persist(plugin.metadata(), authoritative);
            return plugin;
        } catch (RuntimeException exception) {
            if (pluginId != null) {
                try { manager.stopPlugin(pluginId); } catch (RuntimeException ignored) {}
                try { manager.unloadPlugin(pluginId); } catch (RuntimeException ignored) {}
                loaded.remove(pluginId);
            }
            throw exception;
        }
    }

    private LoadedPluginDetails details(PluginWrapper wrapper) {
        if (wrapper == null || !(wrapper.getPlugin() instanceof Pf4jPluginBridge bridge)) {
            throw new IllegalStateException("Plugin does not use the QQBot PF4J bridge");
        }
        String pluginId = requirePluginId(wrapper.getPluginId());
        BotPluginFactory factory = bridge.factory();
        if (!pluginId.equals(factory.pluginId())) {
            throw new IllegalStateException("Plugin descriptor id does not match factory id");
        }
        Path path = wrapper.getPluginPath().toAbsolutePath().normalize();
        String name = pluginId;
        String api = wrapper.getDescriptor().getRequires();
        if (api == null || api.isBlank()) api = PluginApiVersion.CURRENT;
        String schemaPath = null;
        String defaultConfigurationPath = null;
        Set<String> capabilities = new HashSet<>();
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String declared = manifest.getMainAttributes().getValue("Plugin-Name");
                if (declared != null && !declared.isBlank()) name = declared.strip();
                schemaPath = manifest.getMainAttributes().getValue("Plugin-Config-Schema");
                defaultConfigurationPath = manifest.getMainAttributes().getValue("Plugin-Default-Config");
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
        if (defaultConfigurationPath == null || defaultConfigurationPath.isBlank()) {
            throw new IllegalStateException("Plugin manifest must declare Plugin-Default-Config");
        }
        schemaPath = requireResourcePath(schemaPath, "Plugin-Config-Schema");
        defaultConfigurationPath = requireResourcePath(defaultConfigurationPath, "Plugin-Default-Config");
        capabilities.add("event.read");
        capabilities.add("http");
        for (String capability : capabilities) {
            if (!SUPPORTED_CAPABILITIES.contains(capability)) {
                throw new IllegalStateException("Unsupported plugin capability: " + capability);
            }
        }
        JsonNode schema = readPluginSchema(path, schemaPath);
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException("Plugin configuration schema must be an object");
        }
        JsonNode defaultConfiguration = readPluginDefaultConfiguration(path, defaultConfigurationPath);
        if (defaultConfiguration == null || !defaultConfiguration.isObject()) {
            throw new IllegalStateException("Plugin default configuration must be a JSON object");
        }
        List<String> defaultErrors = new PluginConfigurationValidator().validate(schema, defaultConfiguration);
        if (!defaultErrors.isEmpty()) {
            throw new IllegalStateException("Plugin default configuration failed schema validation: "
                    + String.join("; ", defaultErrors));
        }
        String normalizedDefault;
        try {
            normalizedDefault = mapper.writeValueAsString(defaultConfiguration);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to normalize plugin default configuration", exception);
        }
        LoadedPluginMetadata metadata = new LoadedPluginMetadata(pluginId, name,
                wrapper.getDescriptor().getVersion(), api, path, sha256(path), factory.getClass().getName(),
                schemaPath, defaultConfigurationPath, normalizedDefault, capabilities);
        return new LoadedPluginDetails(metadata, factory, schema);
    }

    private JsonNode readPluginSchema(Path pluginPath, String resourcePath) {
        try (JarFile jar = new JarFile(pluginPath.toFile(), false)) {
            JarEntry entry = requirePluginResource(jar, resourcePath,
                    "Plugin configuration schema resource is missing");
            try (InputStream input = jar.getInputStream(entry)) {
                return mapper.readTree(input);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read plugin configuration schema", exception);
        }
    }

    private JsonNode readPluginDefaultConfiguration(Path pluginPath, String resourcePath) {
        try (JarFile jar = new JarFile(pluginPath.toFile(), false)) {
            JarEntry entry = requirePluginResource(jar, resourcePath,
                    "Plugin default configuration resource is missing");
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(MAX_CONFIGURATION_BYTES + 1);
                if (bytes.length > MAX_CONFIGURATION_BYTES) {
                    throw new IllegalStateException("Plugin default configuration cannot exceed 64 KiB");
                }
                return mapper.readTree(bytes);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read plugin default configuration", exception);
        }
    }

    private static JarEntry requirePluginResource(JarFile jar, String resourcePath, String missingMessage) {
        JarEntry entry = jar.getJarEntry(resourcePath);
        if (entry == null || entry.isDirectory()) throw new IllegalStateException(missingMessage);
        return entry;
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
        finishStop(handle);
    }

    private void finishStop(InstanceHandle handle) {
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

    private static String requirePluginId(String pluginId) {
        if (pluginId == null || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalArgumentException(
                    "Plugin id must be 1-128 lowercase ASCII letters, digits, dots, underscores or hyphens, "
                            + "and must start and end with a letter or digit");
        }
        int dot = pluginId.indexOf('.');
        String windowsStem = dot < 0 ? pluginId : pluginId.substring(0, dot);
        if (WINDOWS_RESERVED_PLUGIN_ID_STEMS.contains(windowsStem)) {
            throw new IllegalArgumentException("Plugin id uses a reserved Windows directory name: " + pluginId);
        }
        return pluginId;
    }

    private static String requireResourcePath(String value, String attribute) {
        String path = value.strip();
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            throw new IllegalStateException(attribute + " must name a relative JAR resource");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalStateException(attribute + " must name a normalized JAR resource");
            }
        }
        return path;
    }

    private record LoadedPlugin(LoadedPluginMetadata metadata, BotPluginFactory factory, JsonNode schema) {}
    private record LoadedPluginDetails(LoadedPluginMetadata metadata, BotPluginFactory factory, JsonNode schema) {}
    private record InstanceHandle(UUID bindingId, BotId botId, String pluginId, long revision, BotPlugin plugin,
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
