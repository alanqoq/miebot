package com.mieai.qqbot.protocol.json

/** Raised when a protocol payload cannot be encoded or decoded. */
class JsonCodecException(message: String, cause: Throwable) : RuntimeException(message, cause)
