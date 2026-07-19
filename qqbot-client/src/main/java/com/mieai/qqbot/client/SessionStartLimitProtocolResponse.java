package com.mieai.qqbot.client;

record SessionStartLimitProtocolResponse(
        int total, int remaining, long reset_after, int max_concurrency) {}
