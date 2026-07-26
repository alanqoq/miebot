package com.mieai.qqbot.admin.events

import com.mieai.qqbot.persistence.plugin.PluginDeliveryQueueStats

data class PluginDeliveryQueueStatsResponse(
    val totalCount: Long,
    val pendingCount: Long,
    val inProgressCount: Long,
    val retryWaitCount: Long,
    val succeededCount: Long,
    val deadLetterCount: Long,
    val pausedCount: Long,
) {
    companion object {
        fun from(stats: PluginDeliveryQueueStats): PluginDeliveryQueueStatsResponse =
            PluginDeliveryQueueStatsResponse(
                stats.totalCount,
                stats.pendingCount,
                stats.inProgressCount,
                stats.retryWaitCount,
                stats.succeededCount,
                stats.deadLetterCount,
                stats.pausedCount,
            )
    }
}
