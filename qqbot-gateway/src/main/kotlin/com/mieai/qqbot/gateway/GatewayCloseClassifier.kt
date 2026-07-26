package com.mieai.qqbot.gateway


/** QQ-specific WebSocket close-code policy. */
class GatewayCloseClassifier private constructor() {
    companion object {
        fun classify(statusCode: Int, resumable: Boolean): GatewayCloseDecision = when {
            statusCode == 4004 -> decision(
                statusCode,
                GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.AUTHENTICATION_FAILURE,
            )

            statusCode == 4006 || statusCode == 4007 -> decision(
                statusCode,
                GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.INVALID_SESSION,
            )

            statusCode == 4008 -> decision(
                statusCode,
                GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.RATE_LIMITED,
            )

            statusCode == 4009 -> decision(
                statusCode,
                if (resumable) GatewayCloseDisposition.RESUME else GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.CONNECTION_CLOSED,
            )

            statusCode in 4900..4913 -> decision(
                statusCode,
                GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.CONNECTION_CLOSED,
            )

            statusCode == 4001 ||
                statusCode == 4002 ||
                statusCode in 4010..4014 ||
                statusCode == 4914 ||
                statusCode == 4915 -> decision(
                statusCode,
                GatewayCloseDisposition.STOP,
                GatewayReconnectCause.CONNECTION_CLOSED,
            )

            else -> decision(
                statusCode,
                if (resumable) GatewayCloseDisposition.RESUME else GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.CONNECTION_CLOSED,
            )
        }

        private fun decision(
            code: Int,
            disposition: GatewayCloseDisposition,
            cause: GatewayReconnectCause,
        ): GatewayCloseDecision = GatewayCloseDecision(code, disposition, cause)
    }
}
