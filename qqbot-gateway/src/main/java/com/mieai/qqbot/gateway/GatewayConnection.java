package com.mieai.qqbot.gateway;

import java.util.concurrent.CompletionStage;

/** One established Gateway WebSocket connection. */
public interface GatewayConnection {
    CompletionStage<Void> sendText(String payload);

    CompletionStage<Void> close();
}
