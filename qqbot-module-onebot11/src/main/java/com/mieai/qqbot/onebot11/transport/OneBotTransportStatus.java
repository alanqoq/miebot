package com.mieai.qqbot.onebot11.transport;

public record OneBotTransportStatus(
        String state,
        boolean forwardListening,
        int forwardConnections,
        boolean reverseConnected,
        String lastError) {

    public static OneBotTransportStatus disabled() {
        return new OneBotTransportStatus("DISABLED", false, 0, false, null);
    }

    public static OneBotTransportStatus waiting() {
        return new OneBotTransportStatus("WAITING_FOR_BOT", false, 0, false, null);
    }

    public static OneBotTransportStatus failed(String error) {
        return new OneBotTransportStatus("FAILED", false, 0, false, error);
    }
}
