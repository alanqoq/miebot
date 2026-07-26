package com.mieai.qqbot.persistence.plugin

import java.util.UUID

class PluginDeliveryTransitionException(
    deliveryId: UUID,
    fencingToken: Long,
    targetStatus: PluginDeliveryStatus,
) : RuntimeException(
    "Plugin delivery $deliveryId could not transition to $targetStatus with fencing token $fencingToken",
)
