package com.mieai.qqbot.admin.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.outbox.OutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxPage;
import com.mieai.qqbot.persistence.outbox.OutboxQuery;
import com.mieai.qqbot.persistence.outbox.OutboxQueueStats;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxAdministrationServiceTest {
    private static final UUID JOB_ID = UUID.fromString("41000000-0000-0000-0000-000000000001");
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440001");
    private static final Instant CREATED_AT = Instant.parse("2026-07-18T06:14:58Z");

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final BotRepository botRepository = mock(BotRepository.class);
    private final OutboxAdministrationService service =
            new OutboxAdministrationService(outboxRepository, botRepository);

    @Test
    void dlqAlwaysBuildsADeadLetterQuery() {
        when(outboxRepository.query(any())).thenReturn(new OutboxPage(List.of(), Optional.empty()));
        when(outboxRepository.statistics()).thenReturn(new OutboxQueueStats(0, 0, 0, 0, 0, 0, 0));
        when(botRepository.findAll()).thenReturn(List.of());

        service.list("50", null, null, null, null, null, null, true);

        verify(outboxRepository).query(new OutboxQuery(
                50,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(OutboxStatus.DEAD_LETTER),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    void rejectsNonDeadLetterStatusBeforeQueryingPersistence() {
        assertThatThrownBy(() -> service.list(
                        "50", null, null, null, null, "PENDING", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status must be DEAD_LETTER for the DLQ view");

        verifyNoInteractions(outboxRepository, botRepository);
    }

    @Test
    void truncatesDetailPayloadByUtf8BytesAndNeverReturnsLeaseOwner() {
        String emoji = "\ud83d\ude00";
        String payload = emoji.repeat(OutboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES / 4 + 1);
        when(outboxRepository.findById(JOB_ID)).thenReturn(Optional.of(job(payload)));
        when(botRepository.findAll()).thenReturn(List.of());

        OutboxJobDetailResponse response = service.get(JOB_ID.toString());

        assertThat(response.payloadTruncated()).isTrue();
        assertThat(response.payload().getBytes(StandardCharsets.UTF_8))
                .hasSize(OutboxAdministrationService.MAX_DETAIL_PAYLOAD_BYTES);
        assertThat(response.leaseUntil()).isNull();
    }

    @Test
    void omitsDedupKeyFromListButReturnsItInDetail() {
        when(outboxRepository.query(any())).thenReturn(new OutboxPage(
                List.of(job("{}")), Optional.empty()));
        when(outboxRepository.statistics()).thenReturn(new OutboxQueueStats(1, 1, 0, 0, 0, 0, 0));
        when(outboxRepository.findById(JOB_ID)).thenReturn(Optional.of(job("{}")));
        when(botRepository.findAll()).thenReturn(List.of());

        OutboxPageResponse page = service.list("50", null, null, null, null, null, null);
        OutboxJobDetailResponse detail = service.get(JOB_ID.toString());

        assertThat(page.items()).singleElement().satisfies(summary ->
                assertThat(summary.getClass().getRecordComponents())
                        .extracting(java.lang.reflect.RecordComponent::getName)
                        .doesNotContain("dedupKey", "payload", "leaseOwner", "fencingToken"));
        assertThat(detail.dedupKey()).isEqualTo("reply:1");
    }

    private static OutboxJob job(String payload) {
        return new OutboxJob(
                JOB_ID,
                BotEnvironment.PRODUCTION,
                BOT_ID,
                Optional.empty(),
                "SEND_MESSAGE",
                Optional.of("reply:1"),
                payload,
                OutboxStatus.PENDING,
                0,
                CREATED_AT,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                CREATED_AT,
                CREATED_AT,
                Optional.empty());
    }
}
