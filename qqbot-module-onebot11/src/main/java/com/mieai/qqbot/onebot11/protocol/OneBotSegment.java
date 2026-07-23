package com.mieai.qqbot.onebot11.protocol;

import java.util.Map;
import java.util.Objects;

public record OneBotSegment(String type, Map<String, String> data) {
    public OneBotSegment {
        Objects.requireNonNull(type, "type must not be null");
        if (!type.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("message segment type is invalid");
        }
        data = Map.copyOf(Objects.requireNonNull(data, "data must not be null"));
    }
}
