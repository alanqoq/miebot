package com.mieai.qqbot.client

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal class MutableClock private constructor(
    private var currentInstant: Instant,
    private val currentZone: ZoneId,
) : Clock() {
    constructor(instant: Instant) : this(instant, ZoneId.of("UTC"))

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }

    override fun getZone(): ZoneId = currentZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant
}
