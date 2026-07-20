package com.mieai.qqbot.admin.bot;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.MediaAsset;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.outbox.NewOutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class BotMessageAdministrationServiceTest {
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID ASSET_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

    @Mock BotRepository bots;
    @Mock OutboxRepository outbox;
    @Mock MediaAssetStore mediaStore;

    private BotMessageAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new BotMessageAdministrationService(bots, outbox, mediaStore,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(bots.findById(BOT_ID)).thenReturn(Optional.of(bot()));
    }

    @Test
    void rejectsAStagedAssetThatExceedsTheCurrentBotLimitBeforeQueueing() {
        MediaAsset asset = new MediaAsset(ASSET_ID, BOT_ID, QqMediaKind.IMAGE, "photo.png",
                "image/png", 2L * 1024L * 1024L, NOW);
        when(mediaStore.find(BOT_ID, ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.send(BOT_ID.toString(), new SendBotMessageRequest(
                BotMessageKind.MEDIA, QqMessageTargetType.C2C, "user-1", "caption",
                QqMediaKind.IMAGE, null, ASSET_ID, null, null, null, 1)))
                .isInstanceOf(MediaUploadTooLargeException.class);
        verify(outbox, never()).create(any());
    }

    @Test
    void queuesRichMessageAsDurableOutboxPayload() {
        service.send(BOT_ID.toString(), new SendBotMessageRequest(
                BotMessageKind.MARKDOWN, QqMessageTargetType.CHANNEL, "channel-1", null,
                null, null, null, Map.of("content", "**hello**"), null, null, 1));

        ArgumentCaptor<NewOutboxJob> captor = ArgumentCaptor.forClass(NewOutboxJob.class);
        verify(outbox).create(captor.capture());
        assertThat(captor.getValue().jobType()).isEqualTo("QQ_SEND_RICH");
        assertThat(captor.getValue().payload()).contains("MARKDOWN", "channel-1", "**hello**");
    }

    @Test
    void rejectsUnsupportedChannelMediaBeforeQueueing() {
        assertThatThrownBy(() -> service.send(BOT_ID.toString(), new SendBotMessageRequest(
                BotMessageKind.MEDIA, QqMessageTargetType.CHANNEL, "channel-1", null,
                QqMediaKind.AUDIO, "https://cdn.example/audio.mp3", null, null, null, null, 1)))
                .isInstanceOf(BotMessageAdministrationException.class)
                .hasMessageContaining("only support image");
        verify(outbox, never()).create(any());
    }

    @Test
    void rejectsMalformedRichMessageTargetsBeforeQueueing() {
        assertThatThrownBy(() -> service.send(BOT_ID.toString(), new SendBotMessageRequest(
                BotMessageKind.MARKDOWN, QqMessageTargetType.C2C, "bad target", null,
                null, null, null, Map.of("content", "hello"), null, null, 1)))
                .isInstanceOf(BotMessageAdministrationException.class)
                .hasMessageContaining("targetId");
        verify(outbox, never()).create(any());
    }

    private static StoredBot bot() {
        BotDefinition definition = new BotDefinition(BOT_ID, "Test Bot", QqAppId.of("102012345"),
                BotEnvironment.SANDBOX, GatewayIntents.of(512L), ShardSpec.single(), true,
                BotRevision.initial(), NOW, NOW, 1L * 1024L * 1024L);
        return new StoredBot(definition, SecretCiphertext.of("ciphertext", "key"));
    }
}
