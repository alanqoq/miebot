package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.BotId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Atomic file-backed media staging with a hard byte limit while reading. */
public final class FileMediaAssetStore implements MediaAssetStore {
    private final Path root;
    private final Clock clock;

    public FileMediaAssetStore(Path root) { this(root, Clock.systemUTC()); }

    public FileMediaAssetStore(Path root, Clock clock) {
        this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public MediaAsset stage(BotId botId, QqMediaKind kind, String fileName, String contentType,
            InputStream input, long maxBytes) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (maxBytes < 1L) throw new IllegalArgumentException("maxBytes must be positive");
        validateName(fileName);
        validateContentType(contentType);
        validateKind(kind, contentType);
        UUID id = UUID.randomUUID();
        Path directory = root.resolve(botId.toString()).normalize();
        Path target = directory.resolve(id + ".bin").normalize();
        if (!target.startsWith(directory)) throw new IllegalArgumentException("invalid media path");
        Path temporary = directory.resolve(id + ".tmp").normalize();
        Path metadata = directory.resolve(id + ".meta").normalize();
        Path temporaryMetadata = directory.resolve(id + ".meta.tmp").normalize();
        long size = 0L;
        String normalizedName = fileName.strip();
        String normalizedContentType = contentType.strip().toLowerCase(Locale.ROOT);
        Instant createdAt = clock.instant();
        MediaAsset result;
        try {
            Files.createDirectories(directory);
            try (InputStream source = input; var output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) throw new MediaSizeExceededException(maxBytes);
                    output.write(buffer, 0, read);
                }
            }
            result = new MediaAsset(id, botId, kind, normalizedName, normalizedContentType, size, createdAt);
            writeMetadata(temporaryMetadata, result);
            move(temporary, target);
            move(temporaryMetadata, metadata);
            return result;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            deleteQuietly(temporaryMetadata);
            deleteQuietly(target);
            deleteQuietly(metadata);
            throw new IllegalStateException("Unable to stage media asset", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporary);
            deleteQuietly(temporaryMetadata);
            deleteQuietly(target);
            deleteQuietly(metadata);
            throw exception;
        }
    }

    @Override
    public Optional<MediaAsset> find(BotId botId, UUID id) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Path path = path(botId, id);
        try {
            if (!Files.isRegularFile(path)) return Optional.empty();
            long size = Files.size(path);
            Path metadata = metadataPath(botId, id);
            if (!Files.isRegularFile(metadata)) {
                return Optional.of(new MediaAsset(id, botId, QqMediaKind.FILE, id + ".bin",
                        "application/octet-stream", size,
                        Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())));
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(metadata, StandardOpenOption.READ)) {
                properties.load(input);
            }
            QqMediaKind kind = QqMediaKind.valueOf(properties.getProperty("kind"));
            String fileName = properties.getProperty("fileName");
            String contentType = properties.getProperty("contentType");
            long recordedSize = Long.parseLong(properties.getProperty("sizeBytes"));
            Instant createdAt = Instant.parse(properties.getProperty("createdAt"));
            if (recordedSize != size) return Optional.empty();
            return Optional.of(new MediaAsset(id, botId, kind, fileName, contentType, size, createdAt));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect media asset", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to inspect media asset metadata", exception);
        }
    }

    @Override
    public InputStream open(MediaAsset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        try { return Files.newInputStream(path(asset.botId(), asset.id()), StandardOpenOption.READ); }
        catch (IOException exception) { throw new IllegalStateException("Unable to open media asset", exception); }
    }

    @Override
    public void delete(MediaAsset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        try {
            Files.deleteIfExists(path(asset.botId(), asset.id()));
            Files.deleteIfExists(metadataPath(asset.botId(), asset.id()));
        }
        catch (IOException exception) { throw new IllegalStateException("Unable to delete media asset", exception); }
    }

    private Path path(BotId botId, UUID id) {
        Path directory = root.resolve(botId.toString()).normalize();
        Path path = directory.resolve(id + ".bin").normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("invalid media path");
        return path;
    }

    private Path metadataPath(BotId botId, UUID id) {
        Path directory = root.resolve(botId.toString()).normalize();
        Path path = directory.resolve(id + ".meta").normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("invalid media path");
        return path;
    }

    private static void writeMetadata(Path path, MediaAsset asset) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("kind", asset.kind().name());
        properties.setProperty("fileName", asset.fileName());
        properties.setProperty("contentType", asset.contentType());
        properties.setProperty("sizeBytes", Long.toString(asset.sizeBytes()));
        properties.setProperty("createdAt", asset.createdAt().toString());
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            properties.store(output, "QQBot media asset metadata");
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static void validateName(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.contains("..")
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("fileName is invalid");
    }

    private static void validateContentType(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("contentType is invalid");
        }
    }

    private static void validateKind(QqMediaKind kind, String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        boolean valid = switch (kind) {
            case IMAGE -> normalized.startsWith("image/");
            case VIDEO -> normalized.startsWith("video/");
            case AUDIO -> normalized.startsWith("audio/");
            case FILE -> normalized.startsWith("application/") || normalized.startsWith("text/")
                    || normalized.equals("application/octet-stream");
        };
        if (!valid) throw new IllegalArgumentException("contentType does not match media kind");
    }

    public static final class MediaSizeExceededException extends IllegalArgumentException {
        private final long maxBytes;
        public MediaSizeExceededException(long maxBytes) { super("media upload exceeds " + maxBytes + " bytes"); this.maxBytes = maxBytes; }
        public long maxBytes() { return maxBytes; }
    }
}
