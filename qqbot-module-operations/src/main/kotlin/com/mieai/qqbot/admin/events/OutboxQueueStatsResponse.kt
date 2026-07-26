package com.mieai.qqbot.admin.events

import com.mieai.qqbot.persistence.outbox.OutboxQueueStats

/** Queue counters safe for the administrator UI; no lease or payload data is included. */
data class OutboxQueueStatsResponse(
    val totalCount: Long,
    val pendingCount: Long,
    val inProgressCount: Long,
    val retryWaitCount: Long,
    val succeededCount: Long,
    val resultUnknownCount: Long,
    val deadLetterCount: Long,
) {
    companion object {
        fun from(stats: OutboxQueueStats): OutboxQueueStatsResponse = OutboxQueueStatsResponse(
            stats.totalCount,
            stats.pendingCount,
            stats.inProgressCount,
            stats.retryWaitCount,
            stats.succeededCount,
            stats.resultUnknownCount,
            stats.deadLetterCount,
        )
    }
}
