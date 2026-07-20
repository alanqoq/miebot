package com.mieai.qqbot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotId;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMediaAssetStoreTest {
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void persistsMetadataAndCanReopenTheAssetAfterStoreRestart() throws Exception {
        FileMediaAssetStore store = new FileMediaAssetStore(directory, Clock.fixed(NOW, ZoneOffset.UTC));
        byte[] bytes = new byte[] {1, 2, 3, 4};

        MediaAsset staged = store.stage(BOT_ID, QqMediaKind.IMAGE, "photo.png", "image/png",
                new ByteArrayInputStream(bytes), 16L);

        FileMediaAssetStore reopened = new FileMediaAssetStore(directory, Clock.systemUTC());
        MediaAsset found = reopened.find(BOT_ID, staged.id()).orElseThrow();
        assertThat(found).isEqualTo(staged);
        try (var input = reopened.open(found)) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }

        reopened.delete(found);
        assertThat(reopened.find(BOT_ID, staged.id())).isEmpty();
        try (var files = Files.list(directory.resolve(BOT_ID.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsOversizedContentAndRemovesPartialFiles() throws Exception {
        FileMediaAssetStore store = new FileMediaAssetStore(directory, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> store.stage(BOT_ID, QqMediaKind.FILE, "data.bin",
                "application/octet-stream", new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 3L))
                .isInstanceOf(FileMediaAssetStore.MediaSizeExceededException.class);

        Path botDirectory = directory.resolve(BOT_ID.toString());
        try (var files = Files.list(botDirectory)) {
            assertThat(files).isEmpty();
        }
    }
}
