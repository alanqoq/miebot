package com.mieai.qqbot.runtime.configuration;

/** Best-effort, in-process observation of committed bot configuration changes. */
@FunctionalInterface
public interface BotConfigurationChangeListener {
    void onCommitted(BotConfigurationChange change);

    static BotConfigurationChangeListener none() {
        return ignored -> {};
    }
}
