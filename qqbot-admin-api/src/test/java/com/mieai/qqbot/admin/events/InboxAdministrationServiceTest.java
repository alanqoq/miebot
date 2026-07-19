package com.mieai.qqbot.admin.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.InboxStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxAdministrationServiceTest {
    private static final UUID EVENT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440001");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-18T06:14:58Z");

    private final EventInboxRepository inboxRepository = mock(EventInboxRepository.class);
    private final BotRepository botRepository = mock(BotRepository.class);
    private final InboxAdministrationService service =
            new InboxAdministrationService(inboxRepository, botRepository);

    @Test
    void truncatesOversizedUtf8PayloadInLinearCodePointBoundaries() {
        String emoji = "\ud83d\ude00";
        int fittingCodePoints = InboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES / 4;
        String payload = emoji.repeat(fittingCodePoints + 1);
        when(inboxRepository.findById(EVENT_ID)).thenReturn(Optional.of(event(payload)));
        when(botRepository.findAll()).thenReturn(List.of());

        InboxEventDetailResponse response = service.get(EVENT_ID.toString());

        assertThat(response.payloadTruncated()).isTrue();
        assertThat(response.payload().getBytes(StandardCharsets.UTF_8))
                .hasSize(InboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES);
        assertThat(response.payload().codePointCount(0, response.payload().length()))
                .isEqualTo(fittingCodePoints);
        assertThat(response.payload()).endsWith(emoji);
    }

    @Test
    void rejectsLimitAboveThePublicMaximumBeforeQueryingPersistence() {
        assertThatThrownBy(() -> service.list("101", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");

        verifyNoInteractions(inboxRepository, botRepository);
    }

    private static InboxEvent event(String payload) {
        return new InboxEvent(
                EVENT_ID,
                BotEnvironment.PRODUCTION,
                BOT_ID,
                "MESSAGE_CREATE",
                "platform-event-1",
                payload,
                InboxStatus.RECEIVED,
                0,
                RECEIVED_AT,
                Optional.empty(),
                Optional.empty(),
                0L,
                Optional.empty(),
                RECEIVED_AT,
                RECEIVED_AT);
    }
}
