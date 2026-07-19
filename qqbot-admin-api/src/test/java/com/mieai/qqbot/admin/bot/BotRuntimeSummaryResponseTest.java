package com.mieai.qqbot.admin.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeFailure;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus;
import com.mieai.qqbot.runtime.supervisor.BotSessionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotRuntimeSummaryResponseTest {
    private static final BotId BOT_ID = BotId.of(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void mapsOneConsistentSnapshotAndOnlyCountsOnlineBotsAsConnected() {
        BotRuntimeStatus online = status(
                BOT_ID,
                true,
                BotRuntimeState.ONLINE,
                Optional.of(OBSERVED_AT.minusSeconds(60)),
                Optional.of(OBSERVED_AT.minusSeconds(5)),
                Optional.of(OBSERVED_AT.minusSeconds(10)),
                Optional.of(new BotSessionSnapshot("gateway-session", 42L)),
                2L,
                Optional.of(new BotRuntimeFailure(
                        "TRANSPORT_FAILURE", "QQ Gateway transport failed", true)),
                Optional.of(OBSERVED_AT.minusSeconds(30)));
        BotRuntimeStatus reconnecting = status(
                BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440001")),
                true,
                BotRuntimeState.RECONNECTING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1L,
                Optional.empty(),
                Optional.empty());
        BotRuntimeStatus disabled = status(
                BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440002")),
                false,
                BotRuntimeState.DISABLED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0L,
                Optional.empty(),
                Optional.empty());

        BotRuntimeSummaryResponse response =
                BotRuntimeSummaryResponse.from(List.of(online, reconnecting, disabled), OBSERVED_AT);

        assertThat(response.totalCount()).isEqualTo(3);
        assertThat(response.enabledCount()).isEqualTo(2);
        assertThat(response.connectedCount()).isEqualTo(1);
        assertThat(response.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(response.bots()).hasSize(3);

        BotRuntimeStatusResponse mapped = response.bots().getFirst();
        assertThat(mapped.botId()).isEqualTo(BOT_ID.value());
        assertThat(mapped.configurationRevision()).isEqualTo(3L);
        assertThat(mapped.enabled()).isTrue();
        assertThat(mapped.state()).isEqualTo("ONLINE");
        assertThat(mapped.connectedAt()).isEqualTo(OBSERVED_AT.minusSeconds(60));
        assertThat(mapped.lastHeartbeatAt()).isEqualTo(OBSERVED_AT.minusSeconds(5));
        assertThat(mapped.lastDispatchAt()).isEqualTo(OBSERVED_AT.minusSeconds(10));
        assertThat(mapped.reconnectCount()).isEqualTo(2L);
        assertThat(mapped.session()).isEqualTo(new BotRuntimeSessionResponse("gateway-session", 42L));
        assertThat(mapped.lastError()).isEqualTo(new BotRuntimeErrorResponse(
                "TRANSPORT_FAILURE",
                "QQ Gateway transport failed",
                OBSERVED_AT.minusSeconds(30)));
        assertThatThrownBy(() -> response.bots().add(mapped))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static BotRuntimeStatus status(
            BotId botId,
            boolean enabled,
            BotRuntimeState state,
            Optional<Instant> readyAt,
            Optional<Instant> heartbeatAt,
            Optional<Instant> dispatchAt,
            Optional<BotSessionSnapshot> session,
            long reconnectCount,
            Optional<BotRuntimeFailure> failure,
            Optional<Instant> failureAt) {
        return new BotRuntimeStatus(
                botId,
                BotRevision.of(3L),
                enabled,
                state,
                OBSERVED_AT.minusSeconds(90),
                Optional.of(OBSERVED_AT.minusSeconds(120)),
                readyAt,
                heartbeatAt,
                dispatchAt,
                session,
                reconnectCount,
                failure,
                failureAt);
    }
}
