package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.gateway.GatewayScheduler;

interface RuntimeScheduler extends GatewayScheduler, AutoCloseable {
    @Override
    void close();
}
