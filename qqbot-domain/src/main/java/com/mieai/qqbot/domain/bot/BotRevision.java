package com.mieai.qqbot.domain.bot;

/** Monotonically increasing optimistic-lock revision for bot configuration. */
public record BotRevision(long value) implements Comparable<BotRevision> {
    private static final BotRevision INITIAL = new BotRevision(1L);

    public BotRevision {
        if (value < 1L) {
            throw new IllegalArgumentException("value must be positive");
        }
    }

    public static BotRevision initial() {
        return INITIAL;
    }

    public static BotRevision of(long value) {
        return value == 1L ? INITIAL : new BotRevision(value);
    }

    public BotRevision next() {
        return new BotRevision(Math.incrementExact(value));
    }

    @Override
    public int compareTo(BotRevision other) {
        return Long.compare(value, other.value);
    }
}
