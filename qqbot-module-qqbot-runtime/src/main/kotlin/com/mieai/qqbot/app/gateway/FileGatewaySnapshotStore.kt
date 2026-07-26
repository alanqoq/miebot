package com.mieai.qqbot.app.gateway

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.mieai.qqbot.gateway.GatewaySessionSnapshot
import com.mieai.qqbot.gateway.GatewaySnapshotStore
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** Atomic file-backed resume state for one bot shard. */
class FileGatewaySnapshotStore(
    file: Path,
    configurationFingerprint: String,
    objectMapper: ObjectMapper,
    private val executor: Executor,
) : GatewaySnapshotStore {
    private val monitor = Any()
    val file: Path = file.toAbsolutePath().normalize()
    private val configurationFingerprint = requireText(
        configurationFingerprint,
        "configurationFingerprint",
    )
    private val objectMapper: ObjectMapper = objectMapper
    private val writer: ObjectWriter = this.objectMapper.writerWithDefaultPrettyPrinter()

    override fun load(): CompletionStage<GatewaySessionSnapshot?> =
        CompletableFuture.supplyAsync(::loadBlocking, executor)

    override fun save(snapshot: GatewaySessionSnapshot): CompletionStage<Void> =
        CompletableFuture.runAsync({ saveBlocking(snapshot) }, executor)

    override fun clear(): CompletionStage<Void> =
        CompletableFuture.runAsync(::clearBlocking, executor)

    private fun loadBlocking(): GatewaySessionSnapshot? = synchronized(monitor) {
        if (Files.notExists(file)) {
            return@synchronized null
        }
        try {
            val document: SnapshotDocument? = try {
                objectMapper.readValue(file.toFile(), SnapshotDocument::class.java)
            } catch (_: JsonProcessingException) {
                return@synchronized discardInvalidSnapshot()
            }

            try {
                val checked = requireNotNull(document) {
                    "Gateway resume state must not be null"
                }
                checked.validate()
                if (configurationFingerprint != checked.configurationFingerprint) {
                    discardInvalidSnapshot()
                } else {
                    GatewaySessionSnapshot(checked.sessionId, checked.sequence)
                }
            } catch (_: IllegalArgumentException) {
                discardInvalidSnapshot()
            } catch (_: NullPointerException) {
                discardInvalidSnapshot()
            }
        } catch (exception: IOException) {
            handleReadFailure(exception)
        } catch (exception: SecurityException) {
            handleReadFailure(exception)
        }
    }

    private fun discardInvalidSnapshot(): GatewaySessionSnapshot? {
        Files.deleteIfExists(file)
        return null
    }

    private fun handleReadFailure(exception: Exception): GatewaySessionSnapshot? {
        if (Files.notExists(file)) {
            return null
        }
        throw IllegalStateException("Unable to read Gateway resume state", exception)
    }

    private fun saveBlocking(snapshot: GatewaySessionSnapshot) = synchronized(monitor) {
        val parent = requiredParent()
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = createTemporary(parent)
            writer.writeValue(
                temporary.toFile(),
                SnapshotDocument(
                    CURRENT_VERSION,
                    configurationFingerprint,
                    snapshot.sessionId,
                    snapshot.sequence,
                ),
            )
            moveAtomically(temporary, file)
            temporary = null
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to save Gateway resume state", exception)
        } finally {
            deleteQuietly(temporary)
        }
    }

    private fun clearBlocking() = synchronized(monitor) {
        try {
            Files.deleteIfExists(file)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to clear Gateway resume state", exception)
        }
    }

    private fun requiredParent(): Path = file.parent
        ?: throw IllegalStateException("Gateway resume state requires a parent directory")

    private fun createTemporary(parent: Path): Path = try {
        Files.createTempFile(
            parent,
            file.fileName.toString(),
            ".pending",
            PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE),
        )
    } catch (_: UnsupportedOperationException) {
        Files.createTempFile(parent, file.fileName.toString(), ".pending")
    }

    private data class SnapshotDocument @JsonCreator constructor(
        @JsonProperty("version") val version: Int,
        @JsonProperty("configurationFingerprint") val configurationFingerprint: String,
        @JsonProperty("sessionId") val sessionId: String,
        @JsonProperty("sequence") val sequence: Long,
    ) {
        fun validate() {
            require(version == CURRENT_VERSION) {
                "Unsupported Gateway resume state version"
            }
            requireText(configurationFingerprint, "configurationFingerprint")
            requireText(sessionId, "sessionId")
            require(sequence >= 0L) { "sequence must not be negative" }
        }
    }

    private companion object {
        const val CURRENT_VERSION = 1
        val OWNER_READ_WRITE: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")

        fun moveAtomically(source: Path, target: Path) {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        fun deleteQuietly(file: Path?) {
            if (file == null) return
            try {
                Files.deleteIfExists(file)
            } catch (_: IOException) {
                // A committed state is unaffected by a stale pending file.
            }
        }

        fun requireText(value: String, name: String): String {
            require(value.isNotBlank()) { "$name must not be blank" }
            return value
        }
    }
}
