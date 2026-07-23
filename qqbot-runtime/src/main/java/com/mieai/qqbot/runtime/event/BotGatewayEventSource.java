package com.mieai.qqbot.runtime.event;

/** Read-only, live Gateway event source. Events are not replayed to late subscribers. */
public interface BotGatewayEventSource {
    AutoCloseable subscribe(BotGatewayEventListener listener);
}
