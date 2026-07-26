package com.mieai.qqbot.persistence.plugin

/** One immutable page of plugin deliveries and an optional next-page cursor. */
data class PluginDeliveryPage private constructor(
    val deliveries: List<PluginDelivery>,
    val nextCursor: String?,
    private val normalized: Boolean,
) {
    constructor(deliveries: List<PluginDelivery>, nextCursor: String?) : this(
        deliveries.toList(),
        nextCursor,
        true,
    )
}
