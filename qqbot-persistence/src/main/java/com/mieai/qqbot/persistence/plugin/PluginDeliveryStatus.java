package com.mieai.qqbot.persistence.plugin;

public enum PluginDeliveryStatus {
    PENDING, IN_PROGRESS, RETRY_WAIT, SUCCEEDED, DEAD_LETTER, PAUSED;

    public boolean terminal() {
        return this == SUCCEEDED || this == DEAD_LETTER;
    }
}
