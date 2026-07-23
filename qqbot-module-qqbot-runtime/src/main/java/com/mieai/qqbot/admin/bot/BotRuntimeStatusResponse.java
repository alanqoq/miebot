package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.runtime.supervisor.BotRuntimeFailure;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BotRuntimeStatusResponse(
        UUID botId,
        long configurationRevision,
        boolean enabled,
        String state,
        Instant stateChangedAt,
        Instant connectedAt,
        Instant lastHeartbeatAt,
        Instant lastDispatchAt,
        long reconnectCount,
        BotRuntimeSessionResponse session,
        BotRuntimeErrorResponse lastError) {

    static BotRuntimeStatusResponse from(BotRuntimeStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        BotRuntimeErrorResponse lastError = status.lastFailure()
                .map(failure -> error(failure, status.lastFailureAt().orElseThrow()))
                .orElse(null);
        return new BotRuntimeStatusResponse(
                status.botId().value(),
                status.configurationRevision().value(),
                status.desiredEnabled(),
                status.state().name(),
                status.stateChangedAt(),
                status.readyAt().orElse(null),
                status.lastHeartbeatAt().orElse(null),
                status.lastDispatchAt().orElse(null),
                status.reconnectCount(),
                status.session().map(BotRuntimeSessionResponse::from).orElse(null),
                lastError);
    }

    private static BotRuntimeErrorResponse error(BotRuntimeFailure failure, Instant occurredAt) {
        return new BotRuntimeErrorResponse(failure.code(), failure.message(), occurredAt);
    }
}
