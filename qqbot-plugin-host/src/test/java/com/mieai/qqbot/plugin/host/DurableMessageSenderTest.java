package com.mieai.qqbot.plugin.host;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.MediaAsset;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.plugin.api.MediaKind;
import com.mieai.qqbot.plugin.api.MessageTarget;
import com.mieai.qqbot.plugin.api.MessageTargetType;
import com.mieai.qqbot.plugin.api.StagedMedia;
import com.mieai.qqbot.plugin.api.StagedMediaMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DurableMessageSenderTest {
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID ASSET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    @Mock OutboxRepository outbox;
    @Mock MediaAssetStore mediaStore;

    @Test
    void rejectsForgedStagedMediaMetadataBeforeCreatingAnOutboxJob() {
        MediaAsset stored = new MediaAsset(ASSET_ID, BOT_ID, QqMediaKind.IMAGE,
                "photo.png", "image/png", 4L, NOW);
        when(mediaStore.find(BOT_ID, ASSET_ID)).thenReturn(Optional.of(stored));
        DurableMessageSender sender = new DurableMessageSender(UUID.randomUUID(), BOT_ID,
                BotEnvironment.SANDBOX, outbox, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), new BindingCapabilityGuard(), mediaStore,
                16L * 1024L * 1024L);
        StagedMedia forged = new StagedMedia(ASSET_ID, MediaKind.AUDIO, "photo.png", 4L);
        StagedMediaMessage message = new StagedMediaMessage(
                new MessageTarget(MessageTargetType.C2C, "user-1"), forged,
                Optional.empty(), Optional.empty(), Optional.empty(), 1,
                Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> sender.enqueue(message).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outbox);
    }
}
