package com.mieai.qqbot.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.gateway.GatewaySessionSnapshot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGatewaySnapshotStoreTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void savesLoadsAndClearsOneBotShardState() {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        FileGatewaySnapshotStore store = store(stateFile, "fingerprint-a");
        GatewaySessionSnapshot snapshot = new GatewaySessionSnapshot("session-a", 42L);

        store.save(snapshot).toCompletableFuture().join();

        assertThat(stateFile).isRegularFile();
        assertThat(store.load().toCompletableFuture().join()).contains(snapshot);

        store.clear().toCompletableFuture().join();
        assertThat(store.load().toCompletableFuture().join()).isEmpty();
    }

    @Test
    void discardsAResumeStateWhenTheRuntimeFingerprintChanges() {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        store(stateFile, "old-fingerprint")
                .save(new GatewaySessionSnapshot("session-a", 42L))
                .toCompletableFuture()
                .join();

        assertThat(store(stateFile, "new-fingerprint").load().toCompletableFuture().join())
                .isEmpty();
        assertThat(stateFile).doesNotExist();
    }

    @Test
    void deletesCorruptJsonAndFallsBackToIdentify() throws Exception {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, "{not-json");

        FileGatewaySnapshotStore store = store(stateFile, "fingerprint-a");
        assertThat(store.load().toCompletableFuture().join()).isEmpty();
        assertThat(stateFile).doesNotExist();
        assertThat(store.load().toCompletableFuture().join()).isEmpty();
    }

    @Test
    void deletesDocumentsWithInvalidRequiredFields() throws Exception {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        Files.createDirectories(stateFile.getParent());
        List<String> invalidDocuments = List.of(
                """
                {"version":1,"configurationFingerprint":"fingerprint-a","sequence":1}
                """,
                """
                {"version":1,"configurationFingerprint":"fingerprint-a","sessionId":" ","sequence":1}
                """,
                """
                {"version":1,"configurationFingerprint":"fingerprint-a","sessionId":"session-a","sequence":-1}
                """);

        for (String document : invalidDocuments) {
            Files.writeString(stateFile, document);
            assertThat(store(stateFile, "fingerprint-a").load().toCompletableFuture().join())
                    .isEmpty();
            assertThat(stateFile).doesNotExist();
        }
    }

    @Test
    void deletesUnsupportedSnapshotVersions() throws Exception {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, """
                {"version":2,"configurationFingerprint":"fingerprint-a","sessionId":"session-a","sequence":1}
                """);

        assertThat(store(stateFile, "fingerprint-a").load().toCompletableFuture().join())
                .isEmpty();
        assertThat(stateFile).doesNotExist();
    }

    @Test
    void reportsRealReadIoErrorsWithoutDeletingThePath() throws Exception {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        Files.createDirectories(stateFile);

        assertThatThrownBy(() -> store(stateFile, "fingerprint-a")
                        .load()
                        .toCompletableFuture()
                        .join())
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessage("Unable to read Gateway resume state");
        assertThat(stateFile).isDirectory();
    }

    @Test
    void reportsDeletionFailureInsteadOfPretendingTheInvalidSnapshotWasDiscarded()
            throws Exception {
        Path stateFile = temporaryDirectory.resolve("gateway/bot-1-0.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, "invalid");
        ObjectMapper replacingMapper = new ObjectMapper() {
            @Override
            public <T> T readValue(File source, Class<T> valueType) throws IOException {
                Path sourcePath = source.toPath();
                Files.delete(sourcePath);
                Files.createDirectory(sourcePath);
                Files.writeString(sourcePath.resolve("undeletable-child"), "still present");
                return null;
            }
        };

        assertThatThrownBy(() -> store(stateFile, "fingerprint-a", replacingMapper)
                        .load()
                        .toCompletableFuture()
                        .join())
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessage("Unable to read Gateway resume state");
        assertThat(stateFile.resolve("undeletable-child")).isRegularFile();
    }

    private static FileGatewaySnapshotStore store(Path file, String fingerprint) {
        return store(file, fingerprint, new ObjectMapper());
    }

    private static FileGatewaySnapshotStore store(
            Path file, String fingerprint, ObjectMapper objectMapper) {
        return new FileGatewaySnapshotStore(
                file, fingerprint, objectMapper, ForkJoinPool.commonPool());
    }
}
