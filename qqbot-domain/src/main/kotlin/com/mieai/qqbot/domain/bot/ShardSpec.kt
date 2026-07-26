package com.mieai.qqbot.domain.bot


/** Zero-based shard index and the total shard count for a Gateway connection. */
data class ShardSpec(val index: Int, val count: Int) {
    init {
        require(index >= 0) { "index must not be negative" }
        require(count > 0) { "count must be positive" }
        require(index < count) { "index must be less than count" }
    }

    companion object {
        private val SINGLE = ShardSpec(0, 1)

        fun single(): ShardSpec = SINGLE
    }
}
