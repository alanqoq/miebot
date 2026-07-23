package com.mieai.qqbot.modules.plugins

import com.mieai.qqbot.admin.plugins.PluginAdministrationException
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import org.springframework.context.SmartLifecycle
import org.springframework.http.HttpStatus
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Coordinates the non-transactional plugin data side of a durable bot deletion. */
class PluginBotDeletionCoordinator(
    private val bots: BotRepository,
    private val host: Pf4jPluginHost,
    private val treeDeleter: (Path) -> Unit = { path -> deleteTreeOnDisk(path) },
) : SmartLifecycle {
    enum class AbortResolution {
        RESTORED,
        DELETION_COMMITTED,
    }

    private data class LockHandle(
        val channel: FileChannel,
        val lock: FileLock,
    )

    private data class PreparedDeletion(
        val botRoot: Path,
        val tombstone: Path?,
        val handle: LockHandle,
    )

    private val preparations = ConcurrentHashMap<BotId, PreparedDeletion>()

    @Volatile
    private var running = false

    /** Stops local file mutations and atomically hides the bot directory before database deletion. */
    fun prepare(botId: BotId) {
        if (preparations.containsKey(botId)) {
            throw failure(HttpStatus.CONFLICT, "BOT_DELETION_IN_PROGRESS", "Bot deletion is already in progress")
        }

        val dataRoot = requireDataRoot()
        val handle = acquireBotLock(dataRoot, botId)
        val botRoot = botRoot(dataRoot, botId)
        var movedTombstone: Path? = null
        try {
            if (bots.findById(botId).isEmpty) throw BotNotFoundException(botId)

            val pending = pendingTombstones(dataRoot, botId)
            if (pending.size > 1 || pending.isNotEmpty() && Files.exists(botRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(
                    HttpStatus.CONFLICT,
                    "BOT_PLUGIN_DATA_RECOVERY_CONFLICT",
                    "Bot plugin data has conflicting deletion tombstones",
                )
            }

            val tombstone = if (pending.isNotEmpty()) {
                pending.single().also(::requireDirectDirectory)
            } else if (Files.exists(botRoot, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectDirectory(botRoot)
                dataRoot.resolve("$TOMBSTONE_PREFIX${botId}-${UUID.randomUUID()}").also { target ->
                    moveAtomic(botRoot, target, "Unable to isolate bot plugin data before deletion")
                    movedTombstone = target
                }
            } else {
                null
            }

            val prepared = PreparedDeletion(botRoot, tombstone, handle)
            if (preparations.putIfAbsent(botId, prepared) != null) {
                throw failure(HttpStatus.CONFLICT, "BOT_DELETION_IN_PROGRESS", "Bot deletion is already in progress")
            }
        } catch (exception: RuntimeException) {
            movedTombstone?.let { tombstone ->
                try {
                    if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.exists(botRoot, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        Files.move(tombstone, botRoot, StandardCopyOption.ATOMIC_MOVE)
                    }
                } catch (restoreFailure: IOException) {
                    exception.addSuppressed(restoreFailure)
                }
            }
            closeHandle(handle, exception)
            throw exception
        }
    }

    /** Restores data when the bot still exists, or completes cleanup after an ambiguous commit. */
    fun abort(botId: BotId): AbortResolution {
        val prepared = preparations[botId] ?: return AbortResolution.RESTORED
        var problem: RuntimeException? = null
        var resolution = AbortResolution.RESTORED
        try {
            if (bots.findById(botId).isPresent) {
                restore(prepared)
            } else {
                resolution = AbortResolution.DELETION_COMMITTED
                deletePreparedData(prepared)
            }
        } catch (exception: RuntimeException) {
            problem = exception
        } finally {
            preparations.remove(botId, prepared)
            problem = closeHandle(prepared.handle, problem)
        }
        problem?.let { throw it }
        return resolution
    }

    /** Permanently removes the tombstone after the database transaction committed. */
    fun commit(botId: BotId) {
        val prepared = preparations[botId] ?: throw failure(
            HttpStatus.CONFLICT,
            "BOT_DELETION_NOT_PREPARED",
            "Bot plugin data deletion was not prepared",
        )
        var problem: RuntimeException? = null
        try {
            if (bots.findById(botId).isPresent) {
                throw failure(
                    HttpStatus.CONFLICT,
                    "BOT_DELETION_NOT_COMMITTED",
                    "Bot still exists after its deletion transaction",
                )
            }
            deletePreparedData(prepared)
        } catch (exception: RuntimeException) {
            problem = exception
        } finally {
            preparations.remove(botId, prepared)
            problem = closeHandle(prepared.handle, problem)
        }
        problem?.let { throw it }
    }

    /** Resolves tombstones left by a process failure before any plugin runtime is started. */
    fun recoverPendingDeletions() {
        val dataRoot = requireDataRoot()
        allPendingTombstones(dataRoot).forEach { (botId, tombstones) ->
            val handle = acquireBotLock(dataRoot, botId)
            var problem: RuntimeException? = null
            try {
                val botRoot = botRoot(dataRoot, botId)
                if (bots.findById(botId).isPresent) {
                    if (tombstones.size != 1 || Files.exists(botRoot, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure(
                            HttpStatus.CONFLICT,
                            "BOT_PLUGIN_DATA_RECOVERY_CONFLICT",
                            "Bot plugin data cannot be recovered unambiguously",
                        )
                    }
                    requireDirectDirectory(tombstones.single())
                    moveAtomic(tombstones.single(), botRoot, "Unable to restore bot plugin data")
                } else {
                    tombstones.forEach(::deleteTreeChecked)
                    if (Files.exists(botRoot, LinkOption.NOFOLLOW_LINKS)) {
                        requireDirectDirectory(botRoot)
                        deleteTreeChecked(botRoot)
                    }
                }
            } catch (exception: RuntimeException) {
                problem = exception
            } finally {
                problem = closeHandle(handle, problem)
            }
            problem?.let { throw it }
        }
    }

    override fun start() {
        if (running) return
        recoverPendingDeletions()
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 1_300

    private fun restore(prepared: PreparedDeletion) {
        val tombstone = prepared.tombstone ?: return
        if (!Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(prepared.botRoot, LinkOption.NOFOLLOW_LINKS)) return
            throw failure(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "BOT_PLUGIN_DATA_RESTORE_FAILED",
                "Bot plugin data tombstone is missing",
            )
        }
        if (Files.exists(prepared.botRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                HttpStatus.CONFLICT,
                "BOT_PLUGIN_DATA_RECOVERY_CONFLICT",
                "Bot plugin data directory already exists while restoring its tombstone",
            )
        }
        moveAtomic(tombstone, prepared.botRoot, "Unable to restore bot plugin data after database failure")
    }

    private fun deletePreparedData(prepared: PreparedDeletion) {
        var problem: RuntimeException? = null
        listOfNotNull(prepared.tombstone, prepared.botRoot).distinct().forEach { path ->
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
            try {
                requireDirectDirectory(path)
                deleteTreeChecked(path)
            } catch (exception: RuntimeException) {
                if (problem == null) problem = exception else problem?.addSuppressed(exception)
            }
        }
        problem?.let { throw it }
    }

    private fun requireDataRoot(): Path {
        val dataRoot = host.pluginDataRoot().toAbsolutePath().normalize()
        try {
            Files.createDirectories(dataRoot)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_DIRECTORY_UNAVAILABLE", "Plugin data root is unavailable")
        }
        if (!Files.isDirectory(dataRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dataRoot)) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_DIRECTORY_INVALID", "Plugin data root must be a direct directory")
        }
        return dataRoot
    }

    private fun acquireBotLock(dataRoot: Path, botId: BotId): LockHandle {
        val lockDirectory = dataRoot.resolve(LOCK_DIRECTORY)
        val lockFile = lockDirectory.resolve("bot-${botId}.lock")
        var channel: FileChannel? = null
        try {
            if (Files.exists(lockDirectory, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isDirectory(lockDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockDirectory))
            ) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_LOCK_INVALID", "Plugin data lock directory is invalid")
            }
            Files.createDirectories(lockDirectory)
            if (Files.isSymbolicLink(lockFile)) {
                throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_LOCK_INVALID", "Plugin data lock path is invalid")
            }
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            return LockHandle(channel, channel.lock())
        } catch (exception: OverlappingFileLockException) {
            try {
                channel?.close()
            } catch (closeFailure: IOException) {
                exception.addSuppressed(closeFailure)
            }
            throw failure(HttpStatus.CONFLICT, "BOT_PLUGIN_DATA_BUSY", "Bot plugin data is being modified")
        } catch (exception: IOException) {
            try {
                channel?.close()
            } catch (closeFailure: IOException) {
                exception.addSuppressed(closeFailure)
            }
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_LOCK_FAILED", "Unable to lock bot plugin data")
        }
    }

    private fun closeHandle(handle: LockHandle, previous: RuntimeException?): RuntimeException? {
        var problem = previous
        try {
            handle.lock.release()
        } catch (exception: IOException) {
            val mapped = failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_LOCK_FAILED", "Unable to release bot plugin data lock")
            mapped.addSuppressed(exception)
            if (problem == null) problem = mapped else problem.addSuppressed(mapped)
        }
        try {
            handle.channel.close()
        } catch (exception: IOException) {
            val mapped = failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_LOCK_FAILED", "Unable to close bot plugin data lock")
            mapped.addSuppressed(exception)
            if (problem == null) problem = mapped else problem.addSuppressed(mapped)
        }
        return problem
    }

    private fun botRoot(dataRoot: Path, botId: BotId): Path = dataRoot.resolve(botId.toString()).normalize().also { root ->
        if (root.parent != dataRoot) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_DIRECTORY_INVALID", "Bot plugin data directory is invalid")
        }
    }

    private fun pendingTombstones(dataRoot: Path, botId: BotId): List<Path> =
        allPendingTombstones(dataRoot)[botId].orEmpty()

    private fun allPendingTombstones(dataRoot: Path): Map<BotId, List<Path>> {
        return try {
            val grouped = mutableMapOf<BotId, MutableList<Path>>()
            Files.list(dataRoot).use { paths ->
                paths.forEach { path ->
                    val match = TOMBSTONE_PATTERN.matchEntire(path.fileName.toString())
                    if (match != null) {
                        grouped.getOrPut(BotId.parse(match.groupValues[1])) { mutableListOf() }.add(path)
                    }
                }
            }
            grouped.mapValues { (_, paths) -> paths.toList() }
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "PLUGIN_DATA_RECOVERY_FAILED", "Unable to inspect bot plugin data tombstones")
        }
    }

    private fun requireDirectDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw failure(HttpStatus.CONFLICT, "PLUGIN_DATA_DIRECTORY_INVALID", "Bot plugin data path is not a direct directory")
        }
    }

    private fun moveAtomic(source: Path, target: Path, message: String) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "BOT_PLUGIN_DATA_TOMBSTONE_FAILED", message)
        }
    }

    private fun deleteTreeChecked(path: Path) {
        try {
            treeDeleter(path)
        } catch (exception: IOException) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "BOT_PLUGIN_DATA_DELETE_FAILED", "Unable to delete bot plugin data tombstone")
        }
    }

    private fun failure(status: HttpStatus, code: String, message: String) =
        PluginAdministrationException(status, code, message)

    companion object {
        private const val LOCK_DIRECTORY = ".locks"
        private const val TOMBSTONE_PREFIX = ".bot-delete-pending-"
        private const val UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val TOMBSTONE_PATTERN = Regex("^\\.bot-delete-pending-($UUID_PATTERN)-$UUID_PATTERN$")

        @Throws(IOException::class)
        private fun deleteTreeOnDisk(path: Path) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                    if (exception != null) throw exception
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
