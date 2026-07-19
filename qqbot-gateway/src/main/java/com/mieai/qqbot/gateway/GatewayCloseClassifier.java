package com.mieai.qqbot.gateway;

/** QQ-specific WebSocket close-code policy. */
public final class GatewayCloseClassifier {
    private GatewayCloseClassifier() {}

    public static GatewayCloseDecision classify(int statusCode, boolean resumable) {
        if (statusCode == 4004) {
            return decision(
                    statusCode,
                    GatewayCloseDisposition.IDENTIFY,
                    GatewayReconnectCause.AUTHENTICATION_FAILURE);
        }
        if (statusCode == 4006 || statusCode == 4007) {
            return decision(statusCode, GatewayCloseDisposition.IDENTIFY, GatewayReconnectCause.INVALID_SESSION);
        }
        if (statusCode == 4008) {
            return decision(statusCode, GatewayCloseDisposition.IDENTIFY, GatewayReconnectCause.RATE_LIMITED);
        }
        if (statusCode == 4009) {
            return decision(
                    statusCode,
                    resumable ? GatewayCloseDisposition.RESUME : GatewayCloseDisposition.IDENTIFY,
                    GatewayReconnectCause.CONNECTION_CLOSED);
        }
        if (statusCode >= 4900 && statusCode <= 4913) {
            return decision(statusCode, GatewayCloseDisposition.IDENTIFY, GatewayReconnectCause.CONNECTION_CLOSED);
        }
        if (statusCode == 4001
                || statusCode == 4002
                || (statusCode >= 4010 && statusCode <= 4014)
                || statusCode == 4914
                || statusCode == 4915) {
            return decision(statusCode, GatewayCloseDisposition.STOP, GatewayReconnectCause.CONNECTION_CLOSED);
        }
        return decision(
                statusCode,
                resumable ? GatewayCloseDisposition.RESUME : GatewayCloseDisposition.IDENTIFY,
                GatewayReconnectCause.CONNECTION_CLOSED);
    }

    private static GatewayCloseDecision decision(
            int code, GatewayCloseDisposition disposition, GatewayReconnectCause cause) {
        return new GatewayCloseDecision(code, disposition, cause);
    }
}
