package com.mieai.qqbot.runtime.supervisor;

/** Stable, transport-independent lifecycle states exposed by the multi-bot runtime. */
public enum BotRuntimeState {
    DISABLED,
    STARTING,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    ONLINE,
    RECONNECTING,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean isRunning() {
        return switch (this) {
            case STARTING, DISCOVERING, CONNECTING, AUTHENTICATING, ONLINE, RECONNECTING -> true;
            case DISABLED, STOPPING, STOPPED, FAILED -> false;
        };
    }
}
