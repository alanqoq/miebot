package com.mieai.qqbot.app.database

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Process-level guard for the unsupported multi-process SQLite deployment mode. */
class SQLiteInstanceLock private constructor(
    private val channel: FileChannel?,
    private val lock: FileLock?,
) : AutoCloseable {
    override fun close() {
        try {
            lock?.release()
        } catch (_: IOException) {
        }
        try {
            channel?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        fun acquire(databasePath: Path?): SQLiteInstanceLock {
            if (databasePath == null) return SQLiteInstanceLock(null, null)
            val normalized = databasePath.toAbsolutePath().normalize()
            val lockPath = normalized.resolveSibling("${normalized.fileName}.instance.lock")
            var channel: FileChannel? = null
            try {
                lockPath.parent?.let(Files::createDirectories)
                channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    throw IllegalStateException(
                        "SQLite database is already owned by another qqbot instance: $normalized",
                    )
                }
                return SQLiteInstanceLock(channel, lock)
            } catch (exception: IOException) {
                closeAfterFailure(channel, exception)
                throw IllegalStateException("Unable to acquire SQLite instance lock", exception)
            } catch (exception: RuntimeException) {
                closeAfterFailure(channel, exception)
                throw exception
            }
        }

        private fun closeAfterFailure(channel: FileChannel?, failure: Throwable) {
            try {
                channel?.close()
            } catch (exception: IOException) {
                failure.addSuppressed(exception)
            }
        }
    }
}
