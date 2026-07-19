package com.mieai.qqbot.domain.bot;

import java.util.Objects;

/** Opaque QQ Gateway intent bit mask that preserves bits unknown to this release. */
public record GatewayIntents(long bits) {
    public static final GatewayIntents NONE = new GatewayIntents(0L);
    public static final GatewayIntents GUILDS = bit(0);
    public static final GatewayIntents GUILD_MEMBERS = bit(1);
    public static final GatewayIntents GUILD_MESSAGES = bit(9);
    public static final GatewayIntents GUILD_MESSAGE_REACTIONS = bit(10);
    public static final GatewayIntents DIRECT_MESSAGE = bit(12);
    public static final GatewayIntents GROUP_AND_C2C_EVENT = bit(25);
    public static final GatewayIntents INTERACTION = bit(26);
    public static final GatewayIntents MESSAGE_AUDIT = bit(27);
    public static final GatewayIntents FORUMS_EVENT = bit(28);
    public static final GatewayIntents AUDIO_ACTION = bit(29);
    public static final GatewayIntents PUBLIC_GUILD_MESSAGES = bit(30);
    public static final GatewayIntents DEFAULT_MESSAGE_EVENTS = GROUP_AND_C2C_EVENT;

    public GatewayIntents {
        if (bits < 0L) {
            throw new IllegalArgumentException("bits must not be negative");
        }
    }

    public static GatewayIntents of(long bits) {
        return bits == 0L ? NONE : new GatewayIntents(bits);
    }

    public boolean isEmpty() {
        return bits == 0L;
    }

    public boolean containsAll(GatewayIntents required) {
        Objects.requireNonNull(required, "required must not be null");
        return (bits & required.bits) == required.bits;
    }

    public GatewayIntents union(GatewayIntents other) {
        Objects.requireNonNull(other, "other must not be null");
        return of(bits | other.bits);
    }

    public GatewayIntents without(GatewayIntents removed) {
        Objects.requireNonNull(removed, "removed must not be null");
        return of(bits & ~removed.bits);
    }

    private static GatewayIntents bit(int index) {
        return new GatewayIntents(1L << index);
    }
}
