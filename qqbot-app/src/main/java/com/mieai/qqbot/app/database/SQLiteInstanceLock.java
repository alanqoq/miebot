package com.mieai.qqbot.app.database;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Process-level guard for the unsupported multi-process SQLite deployment mode. */
final class SQLiteInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SQLiteInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static SQLiteInstanceLock acquire(Path databasePath) {
        if (databasePath == null) return new SQLiteInstanceLock(null, null);
        Path normalized = databasePath.toAbsolutePath().normalize();
        Path lockPath = normalized.resolveSibling(normalized.getFileName() + ".instance.lock");
        try {
            if (lockPath.getParent() != null) Files.createDirectories(lockPath.getParent());
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException exception) { lock = null; }
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("SQLite database is already owned by another qqbot instance: " + normalized);
            }
            return new SQLiteInstanceLock(channel, lock);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to acquire SQLite instance lock", exception);
        }
    }

    @Override public void close() {
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
    }
}
