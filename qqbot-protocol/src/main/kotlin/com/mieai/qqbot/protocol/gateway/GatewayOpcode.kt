package com.mieai.qqbot.protocol.gateway


/** Opcodes used by the QQ Gateway protocol. */
enum class GatewayOpcode(private val codeValue: Int) {
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    RESUME(6),
    RECONNECT(7),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    HTTP_CALLBACK_ACK(12),
    UNKNOWN(-1),
    ;

    fun code(): Int = codeValue

    companion object {
        fun fromCode(code: Int): GatewayOpcode = entries.firstOrNull {
            it != UNKNOWN && it.codeValue == code
        } ?: UNKNOWN
    }
}
