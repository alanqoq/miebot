package com.mieai.qqbot.protocol.json;

/** Factory for protocol JSON codecs. */
public final class JsonCodecs {
    private static final JsonCodec DEFAULT_CODEC = new JacksonJsonCodec();

    private JsonCodecs() {}

    public static JsonCodec defaultCodec() {
        return DEFAULT_CODEC;
    }
}
