package com.mieai.qqbot.onebot11.transport

data class OneBotTransportStatus(
    val state: String,
    val forwardListening: Boolean,
    val forwardConnections: Int,
    val reverseConnected: Boolean,
    val lastError: String?,
) {
    companion object {
        fun disabled() = OneBotTransportStatus("DISABLED", false, 0, false, null)

        fun waiting() = OneBotTransportStatus("WAITING_FOR_BOT", false, 0, false, null)

        fun failed(error: String) = OneBotTransportStatus("FAILED", false, 0, false, error)
    }
}
