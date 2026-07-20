package com.mieai.qqbot.persistence.plugin;

public enum PluginBindingRuntimeState {
    ACTIVE,
    PAUSED,
    QUARANTINED;

    public boolean runnable() {
        return this == ACTIVE;
    }
}
