package com.mieai.qqbot.domain.bot;

/** Zero-based shard index and the total shard count for a Gateway connection. */
public record ShardSpec(int index, int count) {
    private static final ShardSpec SINGLE = new ShardSpec(0, 1);

    public ShardSpec {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (index >= count) {
            throw new IllegalArgumentException("index must be less than count");
        }
    }

    public static ShardSpec single() {
        return SINGLE;
    }
}
