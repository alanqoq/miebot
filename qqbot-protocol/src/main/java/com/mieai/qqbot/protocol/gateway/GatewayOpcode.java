package com.mieai.qqbot.protocol.gateway;

import java.util.Arrays;

/** Opcodes used by the QQ Gateway protocol. */
public enum GatewayOpcode {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    RESUME(6),
    RECONNECT(7),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    HTTP_CALLBACK_ACK(12),
    UNKNOWN(-1);

    private final int code;

    GatewayOpcode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static GatewayOpcode fromCode(int code) {
        return Arrays.stream(values())
                .filter(opcode -> opcode != UNKNOWN && opcode.code == code)
                .findFirst()
                .orElse(UNKNOWN);
    }
}
