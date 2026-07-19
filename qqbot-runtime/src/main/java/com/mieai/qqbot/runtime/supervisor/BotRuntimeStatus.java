package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable diagnostic snapshot for one configured bot. */
public record BotRuntimeStatus(
        BotId botId,
        BotRevision configurationRevision,
        boolean desiredEnabled,
        BotRuntimeState state,
        Instant stateChangedAt,
        Optional<Instant> startedAt,
        Optional<Instant> readyAt,
        Optional<Instant> lastHeartbeatAt,
        Optional<Instant> lastDispatchAt,
        Optional<BotSessionSnapshot> session,
        long reconnectCount,
        Optional<BotRuntimeFailure> lastFailure,
        Optional<Instant> lastFailureAt) {

    public BotRuntimeStatus {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(configurationRevision, "configurationRevision must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(stateChangedAt, "stateChangedAt must not be null");
        startedAt = copy(startedAt, "startedAt");
        readyAt = copy(readyAt, "readyAt");
        lastHeartbeatAt = copy(lastHeartbeatAt, "lastHeartbeatAt");
        lastDispatchAt = copy(lastDispatchAt, "lastDispatchAt");
        session = copy(session, "session");
        if (reconnectCount < 0L) {
            throw new IllegalArgumentException("reconnectCount must not be negative");
        }
        lastFailure = copy(lastFailure, "lastFailure");
        lastFailureAt = copy(lastFailureAt, "lastFailureAt");
        if (lastFailure.isPresent() != lastFailureAt.isPresent()) {
            throw new IllegalArgumentException(
                    "lastFailure and lastFailureAt must either both be present or both be empty");
        }
    }

    private static <T> Optional<T> copy(Optional<T> value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.map(Objects::requireNonNull);
    }
}
