package com.mieai.qqbot.onebot11.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.time.Clock;
import java.util.Objects;

final class OneBotMetaEventFactory {
    private final ObjectMapper objectMapper;
    private final BotSupervisor supervisor;
    private final Clock clock;

    OneBotMetaEventFactory(ObjectMapper objectMapper, BotSupervisor supervisor) {
        this(objectMapper, supervisor, Clock.systemUTC());
    }

    OneBotMetaEventFactory(ObjectMapper objectMapper, BotSupervisor supervisor, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    ObjectNode lifecycle(long selfId) {
        ObjectNode event = base(selfId);
        event.put("meta_event_type", "lifecycle");
        event.put("sub_type", "connect");
        return event;
    }

    ObjectNode heartbeat(BotId botId, long selfId, int intervalMs) {
        boolean online = supervisor.status(botId)
                .map(status -> status.state() == BotRuntimeState.ONLINE).orElse(false);
        ObjectNode event = base(selfId);
        event.put("meta_event_type", "heartbeat");
        ObjectNode status = event.putObject("status");
        status.put("online", online);
        status.put("good", online);
        event.put("interval", intervalMs);
        return event;
    }

    private ObjectNode base(long selfId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("time", clock.instant().getEpochSecond());
        event.put("self_id", selfId);
        event.put("post_type", "meta_event");
        return event;
    }
}
