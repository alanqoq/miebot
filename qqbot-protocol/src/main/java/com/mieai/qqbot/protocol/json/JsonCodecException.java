package com.mieai.qqbot.protocol.json;

import java.io.Serial;

/** Raised when a protocol payload cannot be encoded or decoded. */
public final class JsonCodecException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public JsonCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
