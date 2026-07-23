package com.mieai.qqbot.app.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.mieai.qqbot.gateway.GatewaySessionSnapshot;
import com.mieai.qqbot.gateway.GatewaySnapshotStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Atomic file-backed resume state for one bot shard. */
final class FileGatewaySnapshotStore implements GatewaySnapshotStore {
    private static final int CURRENT_VERSION = 1;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            PosixFilePermissions.fromString("rw-------");

    private final Object monitor = new Object();
    private final Path file;
    private final String configurationFingerprint;
    private final ObjectMapper objectMapper;
    private final ObjectWriter writer;
    private final Executor executor;

    FileGatewaySnapshotStore(
            Path file,
            String configurationFingerprint,
            ObjectMapper objectMapper,
            Executor executor) {
        this.file = Objects.requireNonNull(file, "file must not be null")
                .toAbsolutePath()
                .normalize();
        this.configurationFingerprint = requireText(
                configurationFingerprint, "configurationFingerprint");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        writer = objectMapper.writerWithDefaultPrettyPrinter();
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public CompletionStage<Optional<GatewaySessionSnapshot>> load() {
        return CompletableFuture.supplyAsync(this::loadBlocking, executor);
    }

    @Override
    public CompletionStage<Void> save(GatewaySessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return CompletableFuture.runAsync(() -> saveBlocking(snapshot), executor);
    }

    @Override
    public CompletionStage<Void> clear() {
        return CompletableFuture.runAsync(this::clearBlocking, executor);
    }

    Path file() {
        return file;
    }

    private Optional<GatewaySessionSnapshot> loadBlocking() {
        synchronized (monitor) {
            if (Files.notExists(file)) {
                return Optional.empty();
            }
            try {
                SnapshotDocument document;
                try {
                    document = objectMapper.readValue(file.toFile(), SnapshotDocument.class);
                } catch (JsonProcessingException exception) {
                    return discardInvalidSnapshot();
                }

                try {
                    Objects.requireNonNull(document, "Gateway resume state must not be null");
                    document.validate();
                    if (!configurationFingerprint.equals(document.configurationFingerprint())) {
                        return discardInvalidSnapshot();
                    }
                    return Optional.of(new GatewaySessionSnapshot(
                            document.sessionId(), document.sequence()));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    return discardInvalidSnapshot();
                }
            } catch (IOException | SecurityException exception) {
                if (Files.notExists(file)) {
                    return Optional.empty();
                }
                throw new IllegalStateException("Unable to read Gateway resume state", exception);
            }
        }
    }

    private Optional<GatewaySessionSnapshot> discardInvalidSnapshot() throws IOException {
        Files.deleteIfExists(file);
        return Optional.empty();
    }

    private void saveBlocking(GatewaySessionSnapshot snapshot) {
        synchronized (monitor) {
            Path parent = requiredParent();
            Path temporary = null;
            try {
                Files.createDirectories(parent);
                temporary = createTemporary(parent);
                writer.writeValue(temporary.toFile(), new SnapshotDocument(
                        CURRENT_VERSION,
                        configurationFingerprint,
                        snapshot.sessionId(),
                        snapshot.sequence()));
                moveAtomically(temporary, file);
                temporary = null;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to save Gateway resume state", exception);
            } finally {
                deleteQuietly(temporary);
            }
        }
    }

    private void clearBlocking() {
        synchronized (monitor) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to clear Gateway resume state", exception);
            }
        }
    }

    private Path requiredParent() {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IllegalStateException("Gateway resume state requires a parent directory");
        }
        return parent;
    }

    private Path createTemporary(Path parent) throws IOException {
        try {
            return Files.createTempFile(
                    parent,
                    file.getFileName().toString(),
                    ".pending",
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile(parent, file.getFileName().toString(), ".pending");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A committed state is unaffected by a stale pending file.
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record SnapshotDocument(
            int version,
            String configurationFingerprint,
            String sessionId,
            long sequence) {
        private void validate() {
            if (version != CURRENT_VERSION) {
                throw new IllegalArgumentException("Unsupported Gateway resume state version");
            }
            requireText(configurationFingerprint, "configurationFingerprint");
            requireText(sessionId, "sessionId");
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
        }
    }
}
