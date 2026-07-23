package com.mieai.qqbot.onebot11.protocol;

import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.client.QqMediaDownloader;
import com.mieai.qqbot.domain.bot.BotId;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded local cache used by OneBot get_image/get_record and clean_cache. */
public final class OneBotMediaCache {
    private static final Set<String> RECORD_FORMATS = Set.of(
            "mp3", "amr", "wma", "m4a", "spx", "ogg", "wav", "flac");

    private final Path root;
    private final QqMediaDownloader downloader;

    public OneBotMediaCache(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        downloader = new QqMediaDownloader(
                QqClientOptions.DEFAULT_MAX_MEDIA_BYTES,
                QqClientOptions.DEFAULT_MEDIA_DOWNLOAD_TIMEOUT,
                QqClientOptions.DEFAULT_MAX_MEDIA_REDIRECTS);
    }

    public Path image(BotId botId, String file) {
        return materialize(botId, file, null);
    }

    public Path record(BotId botId, String file, String outputFormat) {
        String format = normalizedFormat(outputFormat);
        URI source = source(file);
        String extension = extension(source.getPath());
        if (extension == null || !extension.equals(format)) {
            throw new IllegalArgumentException(
                    "Record transcoding is unavailable; source format must match out_format");
        }
        return materialize(botId, file, format);
    }

    public void clean(BotId botId) {
        Path directory = botDirectory(botId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                if (!path.normalize().startsWith(directory)) {
                    throw new IllegalStateException("Media cache path escaped its bot directory");
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new CacheIoException(exception);
                }
            });
        } catch (IOException | CacheIoException exception) {
            throw new IllegalStateException("Unable to clean OneBot media cache", exception);
        }
    }

    private Path materialize(BotId botId, String file, String requiredExtension) {
        URI uri = source(file);
        String extension = requiredExtension != null ? requiredExtension : extension(uri.getPath());
        String suffix = extension == null ? ".bin" : "." + extension;
        Path directory = botDirectory(botId);
        Path target = directory.resolve(sha256(uri.toString()) + suffix).normalize();
        if (!target.startsWith(directory)) {
            throw new IllegalStateException("Media cache target escaped its bot directory");
        }
        if (Files.isRegularFile(target)) {
            return target;
        }
        try {
            byte[] bytes = downloader.download(uri).toCompletableFuture().get(30, TimeUnit.SECONDS);
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "download-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            return target;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Media download was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException | IOException exception) {
            throw new IllegalStateException("Unable to cache OneBot media", exception);
        }
    }

    private Path botDirectory(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        Path directory = root.resolve(botId.toString()).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalStateException("Bot media directory escaped cache root");
        }
        return directory;
    }

    private static URI source(String file) {
        if (file == null || file.isBlank() || file.length() > 2_048) {
            throw new IllegalArgumentException("file is invalid");
        }
        URI uri;
        try {
            uri = URI.create(file);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("file must be an HTTPS URL", exception);
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("file must be an HTTPS URL");
        }
        return uri;
    }

    private static String normalizedFormat(String value) {
        if (value == null) throw new IllegalArgumentException("out_format is required");
        String result = value.toLowerCase(Locale.ROOT);
        if (!RECORD_FORMATS.contains(result)) {
            throw new IllegalArgumentException("out_format is unsupported");
        }
        return result;
    }

    private static String extension(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return null;
        String result = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return result.matches("[a-z0-9]{1,8}") ? result : null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class CacheIoException extends RuntimeException {
        private CacheIoException(IOException cause) {
            super(cause);
        }
    }
}
