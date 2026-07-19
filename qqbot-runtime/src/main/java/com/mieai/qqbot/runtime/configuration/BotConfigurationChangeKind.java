package com.mieai.qqbot.runtime.configuration;

/** Kind of persisted bot configuration change observed by the runtime supervisor. */
public enum BotConfigurationChangeKind {
    CREATED,
    UPDATED,
    ENABLED,
    DISABLED,
    DELETED
}
