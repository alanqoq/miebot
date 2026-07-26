package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.BotId
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** Atomic file-backed media staging with a hard byte limit while reading. */
class FileMediaAssetStore(
    root: Path,
    private val clock: Clock = Clock.systemUTC(),
) : MediaAssetStore {
    private val root: Path = root.toAbsolutePath().normalize()

    override fun stage(
        botId: BotId,
        kind: QqMediaKind,
        fileName: String,
        contentType: String,
        input: InputStream,
        maxBytes: Long,
    ): MediaAsset {
        require(maxBytes >= 1L) { "maxBytes must be positive" }
        validateName(fileName)
        validateContentType(contentType)
        validateKind(kind, contentType)

        val id = UUID.randomUUID()
        val directory = root.resolve(botId.toString()).normalize()
        val target = directory.resolve("$id.bin").normalize()
        require(target.startsWith(directory)) { "invalid media path" }
        val temporary = directory.resolve("$id.tmp").normalize()
        val metadata = directory.resolve("$id.meta").normalize()
        val temporaryMetadata = directory.resolve("$id.meta.tmp").normalize()
        var size = 0L
        val normalizedName = fileName.trim()
        val normalizedContentType = contentType.trim().lowercase(Locale.ROOT)
        val createdAt = clock.instant()

        try {
            Files.createDirectories(directory)
            input.use { source ->
                Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        size += read
                        if (size > maxBytes) throw MediaSizeExceededException(maxBytes)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val result = MediaAsset(
                id,
                botId,
                kind,
                normalizedName,
                normalizedContentType,
                size,
                createdAt,
            )
            writeMetadata(temporaryMetadata, result)
            move(temporary, target)
            move(temporaryMetadata, metadata)
            return result
        } catch (exception: IOException) {
            deleteQuietly(temporary)
            deleteQuietly(temporaryMetadata)
            deleteQuietly(target)
            deleteQuietly(metadata)
            throw IllegalStateException("Unable to stage media asset", exception)
        } catch (exception: RuntimeException) {
            deleteQuietly(temporary)
            deleteQuietly(temporaryMetadata)
            deleteQuietly(target)
            deleteQuietly(metadata)
            throw exception
        }
    }

    override fun find(botId: BotId, id: UUID): MediaAsset? {
        val path = path(botId, id)
        try {
            if (!Files.isRegularFile(path)) return null
            val size = Files.size(path)
            val metadata = metadataPath(botId, id)
            if (!Files.isRegularFile(metadata)) {
                return MediaAsset(
                    id,
                    botId,
                    QqMediaKind.FILE,
                    "$id.bin",
                    "application/octet-stream",
                    size,
                    Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()),
                )
            }
            val properties = Properties()
            Files.newInputStream(metadata, StandardOpenOption.READ).use(properties::load)
            val kind = QqMediaKind.valueOf(properties.getProperty("kind"))
            val fileName = properties.getProperty("fileName")
            val contentType = properties.getProperty("contentType")
            val recordedSize = properties.getProperty("sizeBytes").toLong()
            val createdAt = Instant.parse(properties.getProperty("createdAt"))
            if (recordedSize != size) return null
            return MediaAsset(id, botId, kind, fileName, contentType, size, createdAt)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to inspect media asset", exception)
        } catch (exception: RuntimeException) {
            throw IllegalStateException("Unable to inspect media asset metadata", exception)
        }
    }

    override fun open(asset: MediaAsset): InputStream {
        return try {
            Files.newInputStream(path(asset.botId, asset.id), StandardOpenOption.READ)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to open media asset", exception)
        }
    }

    override fun delete(asset: MediaAsset) {
        try {
            Files.deleteIfExists(path(asset.botId, asset.id))
            Files.deleteIfExists(metadataPath(asset.botId, asset.id))
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to delete media asset", exception)
        }
    }

    private fun path(botId: BotId, id: UUID): Path {
        val directory = root.resolve(botId.toString()).normalize()
        val path = directory.resolve("$id.bin").normalize()
        require(path.startsWith(directory)) { "invalid media path" }
        return path
    }

    private fun metadataPath(botId: BotId, id: UUID): Path {
        val directory = root.resolve(botId.toString()).normalize()
        val path = directory.resolve("$id.meta").normalize()
        require(path.startsWith(directory)) { "invalid media path" }
        return path
    }

    companion object {
        private fun writeMetadata(path: Path, asset: MediaAsset) {
            val properties = Properties()
            properties.setProperty("kind", asset.kind.name)
            properties.setProperty("fileName", asset.fileName)
            properties.setProperty("contentType", asset.contentType)
            properties.setProperty("sizeBytes", asset.sizeBytes.toString())
            properties.setProperty("createdAt", asset.createdAt.toString())
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                properties.store(it, "QQBot media asset metadata")
            }
        }

        private fun move(source: Path, target: Path) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target)
            }
        }

        private fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // Cleanup is best effort; the original failure is retained.
            }
        }

        private fun validateName(value: String) {
            require(!value.isBlank() && value.length <= 255 && ".." !in value &&
                '/' !in value && '\\' !in value &&
                value.codePoints().noneMatch(Character::isISOControl)) {
                "fileName is invalid"
            }
        }

        private fun validateContentType(value: String) {
            require(!value.isBlank() && value.length <= 128 &&
                value.codePoints().noneMatch(Character::isWhitespace)) {
                "contentType is invalid"
            }
        }

        private fun validateKind(kind: QqMediaKind, contentType: String) {
            val normalized = contentType.lowercase(Locale.ROOT)
            val valid = when (kind) {
                QqMediaKind.IMAGE -> normalized.startsWith("image/")
                QqMediaKind.VIDEO -> normalized.startsWith("video/")
                QqMediaKind.AUDIO -> normalized.startsWith("audio/")
                QqMediaKind.FILE -> normalized.startsWith("application/") ||
                    normalized.startsWith("text/") || normalized == "application/octet-stream"
            }
            require(valid) { "contentType does not match media kind" }
        }
    }

    class MediaSizeExceededException(private val maxBytesValue: Long) :
        IllegalArgumentException("media upload exceeds $maxBytesValue bytes") {
        fun maxBytes(): Long = maxBytesValue
    }
}
