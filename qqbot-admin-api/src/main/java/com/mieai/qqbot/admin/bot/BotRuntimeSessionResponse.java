package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.runtime.supervisor.BotSessionSnapshot;
import java.util.Objects;

public record BotRuntimeSessionResponse(String id, long sequence) {

    static BotRuntimeSessionResponse from(BotSessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return new BotRuntimeSessionResponse(snapshot.sessionId(), snapshot.sequence());
    }
}
