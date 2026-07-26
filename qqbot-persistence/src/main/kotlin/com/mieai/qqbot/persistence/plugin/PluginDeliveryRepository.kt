package com.mieai.qqbot.persistence.plugin

import java.time.Duration
import java.time.Instant
import java.util.UUID

interface PluginDeliveryRepository {
    fun createIfAbsent(id: UUID, eventId: UUID, bindingId: UUID, handlerId: String, now: Instant): Boolean

    fun findById(id: UUID): PluginDelivery?

    fun query(query: PluginDeliveryQuery): PluginDeliveryPage

    fun statistics(): PluginDeliveryQueueStats

    fun claimNext(leaseOwner: String, now: Instant, leaseDuration: Duration): PluginDelivery?

    fun claimNextOwned(
        leaseOwner: String,
        botLeaseOwner: String,
        now: Instant,
        leaseDuration: Duration,
    ): PluginDelivery?

    fun markSucceeded(id: UUID, fencingToken: Long, now: Instant)

    fun markRetry(id: UUID, fencingToken: Long, now: Instant, availableAt: Instant, error: String)

    fun markDeadLetter(id: UUID, fencingToken: Long, now: Instant, reason: String)

    fun pauseForBinding(bindingId: UUID, now: Instant, reason: String): Int

    fun resumeForBinding(bindingId: UUID, now: Instant): Int
}
