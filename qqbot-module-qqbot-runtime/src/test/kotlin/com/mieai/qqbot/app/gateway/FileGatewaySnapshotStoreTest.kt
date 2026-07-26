package com.mieai.qqbot.app.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.gateway.GatewaySessionSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ForkJoinPool

class FileGatewaySnapshotStoreTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `saves loads and clears one bot shard state`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json")
        val store = store(stateFile, "fingerprint-a")
        val snapshot = GatewaySessionSnapshot("session-a", 42L)
        store.save(snapshot).toCompletableFuture().join()
        assertThat(stateFile).isRegularFile()
        assertThat(store.load().toCompletableFuture().join()).isEqualTo(snapshot)
        store.clear().toCompletableFuture().join()
        assertThat(store.load().toCompletableFuture().join()).isNull()
    }
    @Test fun `discards a resume state when the runtime fingerprint changes`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json")
        store(stateFile, "old-fingerprint").save(GatewaySessionSnapshot("session-a", 42L)).toCompletableFuture().join()
        assertThat(store(stateFile, "new-fingerprint").load().toCompletableFuture().join()).isNull()
        assertThat(stateFile).doesNotExist()
    }
    @Test fun `deletes corrupt json and falls back to identify`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json")
        Files.createDirectories(stateFile.parent); Files.writeString(stateFile, "{not-json")
        val store = store(stateFile, "fingerprint-a")
        assertThat(store.load().toCompletableFuture().join()).isNull(); assertThat(stateFile).doesNotExist()
        assertThat(store.load().toCompletableFuture().join()).isNull()
    }
    @Test fun `deletes documents with invalid required fields`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json"); Files.createDirectories(stateFile.parent)
        listOf("""{"version":1,"configurationFingerprint":"fingerprint-a","sequence":1}""", """{"version":1,"configurationFingerprint":"fingerprint-a","sessionId":" ","sequence":1}""", """{"version":1,"configurationFingerprint":"fingerprint-a","sessionId":"session-a","sequence":-1}""").forEach { document ->
            Files.writeString(stateFile, document)
            assertThat(store(stateFile, "fingerprint-a").load().toCompletableFuture().join()).isNull(); assertThat(stateFile).doesNotExist()
        }
    }
    @Test fun `deletes unsupported snapshot versions`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json"); Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, """{"version":2,"configurationFingerprint":"fingerprint-a","sessionId":"session-a","sequence":1}""")
        assertThat(store(stateFile, "fingerprint-a").load().toCompletableFuture().join()).isNull(); assertThat(stateFile).doesNotExist()
    }
    @Test fun `reports real read io errors without deleting the path`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json"); Files.createDirectories(stateFile)
        assertThatThrownBy { store(stateFile, "fingerprint-a").load().toCompletableFuture().join() }
            .hasCauseInstanceOf(IllegalStateException::class.java).cause().hasMessage("Unable to read Gateway resume state")
        assertThat(stateFile).isDirectory()
    }
    @Test fun `reports deletion failure instead of pretending invalid snapshot was discarded`() {
        val stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json"); Files.createDirectories(stateFile.parent); Files.writeString(stateFile, "invalid")
        val replacingMapper = object : ObjectMapper() {
            @Throws(IOException::class)
            override fun <T> readValue(source: File, valueType: Class<T>): T? {
                val sourcePath = source.toPath(); Files.delete(sourcePath); Files.createDirectory(sourcePath); Files.writeString(sourcePath.resolve("undeletable-child"), "still present"); return null
            }
        }
        assertThatThrownBy { store(stateFile, "fingerprint-a", replacingMapper).load().toCompletableFuture().join() }
            .hasCauseInstanceOf(IllegalStateException::class.java).cause().hasMessage("Unable to read Gateway resume state")
        assertThat(stateFile.resolve("undeletable-child")).isRegularFile()
    }
    private fun store(file: Path, fingerprint: String, objectMapper: ObjectMapper = ObjectMapper()) = FileGatewaySnapshotStore(file, fingerprint, objectMapper, ForkJoinPool.commonPool())
}
