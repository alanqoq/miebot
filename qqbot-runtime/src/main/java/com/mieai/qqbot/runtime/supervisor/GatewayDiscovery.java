package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.client.GatewayBotInfo;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
interface GatewayDiscovery {
    CompletionStage<GatewayBotInfo> discover();
}
