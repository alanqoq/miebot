package com.mieai.qqbot.runtime.event;

@FunctionalInterface
public interface BotGatewayEventListener {
    void onGatewayEvent(BotGatewayEvent event);
}
