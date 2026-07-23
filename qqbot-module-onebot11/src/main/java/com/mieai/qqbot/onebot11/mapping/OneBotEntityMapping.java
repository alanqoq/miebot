package com.mieai.qqbot.onebot11.mapping;

public record OneBotEntityMapping(
        long oneBotId,
        OneBotEntityType type,
        String scopeId,
        String rawId) {}
