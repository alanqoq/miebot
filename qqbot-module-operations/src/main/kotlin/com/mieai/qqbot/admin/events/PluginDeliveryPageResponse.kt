package com.mieai.qqbot.admin.events

import java.time.Instant

data class PluginDeliveryPageResponse private constructor(
    val items: List<PluginDeliverySummaryResponse>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val observedAt: Instant,
    val stats: PluginDeliveryQueueStatsResponse,
    private val normalized: Boolean,
) {
    constructor(
        items: List<PluginDeliverySummaryResponse>,
        nextCursor: String?,
        hasMore: Boolean,
        observedAt: Instant,
        stats: PluginDeliveryQueueStatsResponse,
    ) : this(
        items.toList(),
        nextCursor,
        hasMore,
        observedAt,
        stats,
        true,
    )

}
