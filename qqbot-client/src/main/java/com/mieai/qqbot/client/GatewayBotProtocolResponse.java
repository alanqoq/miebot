package com.mieai.qqbot.client;

record GatewayBotProtocolResponse(
        String url, int shards, SessionStartLimitProtocolResponse session_start_limit) {}
