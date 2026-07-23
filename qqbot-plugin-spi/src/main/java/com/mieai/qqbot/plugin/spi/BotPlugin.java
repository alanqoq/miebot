package com.mieai.qqbot.plugin.spi;

/** Pure Java plugin lifecycle contract. */
public interface BotPlugin {
    default void start() {}

    default void stop() {}
}
