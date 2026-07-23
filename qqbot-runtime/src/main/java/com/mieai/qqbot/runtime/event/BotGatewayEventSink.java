package com.mieai.qqbot.runtime.event;

/** Internal durable-ingress side of the trusted Gateway event stream. */
public interface BotGatewayEventSink {
    void publish(BotGatewayEvent event);

    static BotGatewayEventSink noop() {
        return event -> {};
    }
}
