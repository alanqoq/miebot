package com.mieai.qqbot.onebot11.protocol

import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqMediaDownloader
import com.mieai.qqbot.domain.bot.BotId
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import java.util.Comparator
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded local cache used by OneBot get_image/get_record and clean_cache. */
class OneBotMediaCache(root: Path) {
    private val root = root.toAbsolutePath().normalize()
    private val downloader = QqMediaDownloader(
        QqClientOptions.DEFAULT_MAX_MEDIA_BYTES,
        QqClientOptions.DEFAULT_MEDIA_DOWNLOAD_TIMEOUT,
        QqClientOptions.DEFAULT_MAX_MEDIA_REDIRECTS,
    )

    fun image(botId: BotId, file: String): Path = materialize(botId, file, null)

    fun record(botId: BotId, file: String, outputFormat: String?): Path {
        val format = normalizedFormat(outputFormat)
        val source = source(file)
        val extension = extension(source.path)
        require(extension == format) {
            "Record transcoding is unavailable; source format must match out_format"
        }
        return materialize(botId, file, format)
    }

    fun clean(botId: BotId) {
        val directory = botDirectory(botId)
        if (!Files.exists(directory)) {
            return
        }
        try {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    check(path.normalize().startsWith(directory)) {
                        "Media cache path escaped its bot directory"
                    }
                    try {
                        Files.deleteIfExists(path)
                    } catch (exception: IOException) {
                        throw CacheIoException(exception)
                    }
                }
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to clean OneBot media cache", exception)
        } catch (exception: CacheIoException) {
            throw IllegalStateException("Unable to clean OneBot media cache", exception)
        }
    }

    private fun materialize(botId: BotId, file: String, requiredExtension: String?): Path {
        val uri = source(file)
        val extension = requiredExtension ?: extension(uri.path)
        val suffix = extension?.let { ".$it" } ?: ".bin"
        val directory = botDirectory(botId)
        val target = directory.resolve(sha256(uri.toString()) + suffix).normalize()
        check(target.startsWith(directory)) { "Media cache target escaped its bot directory" }
        if (Files.isRegularFile(target)) {
            return target
        }
        try {
            val bytes = downloader.download(uri).toCompletableFuture().get(30, TimeUnit.SECONDS)
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "download-", ".tmp")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
                Arrays.fill(bytes, 0.toByte())
            }
            return target
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Media download was interrupted", exception)
        } catch (exception: ExecutionException) {
            throw IllegalStateException("Unable to cache OneBot media", exception)
        } catch (exception: TimeoutException) {
            throw IllegalStateException("Unable to cache OneBot media", exception)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to cache OneBot media", exception)
        }
    }

    private fun botDirectory(botId: BotId): Path {
        val directory = root.resolve(botId.toString()).normalize()
        check(directory.startsWith(root)) { "Bot media directory escaped cache root" }
        return directory
    }

    companion object {
        private val RECORD_FORMATS = setOf("mp3", "amr", "wma", "m4a", "spx", "ogg", "wav", "flac")

        private fun source(file: String): URI {
            require(file.isNotBlank() && file.length <= 2_048) { "file is invalid" }
            val uri = try {
                URI.create(file)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("file must be an HTTPS URL", exception)
            }
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
                "file must be an HTTPS URL"
            }
            return uri
        }

        private fun normalizedFormat(value: String?): String {
            requireNotNull(value) { "out_format is required" }
            val result = value.lowercase(Locale.ROOT)
            require(result in RECORD_FORMATS) { "out_format is unsupported" }
            return result
        }

        private fun extension(path: String?): String? {
            if (path == null) return null
            val slash = path.lastIndexOf('/')
            val dot = path.lastIndexOf('.')
            if (dot <= slash || dot == path.length - 1) return null
            return path.substring(dot + 1).lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        }

        private fun sha256(value: String): String = try {
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
            )
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", exception)
        }
    }

    private class CacheIoException(cause: IOException) : RuntimeException(cause)
}
