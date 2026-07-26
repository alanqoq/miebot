package com.mieai.qqbot.persistence.internal

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Internal fixed-width timestamp codec whose output can be ordered lexicographically by SQLite. */
object UtcTimestampCodec {
    private val formatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendInstant(9)
        .toFormatter(Locale.ROOT)

    fun format(value: Instant): String = formatter.format(value)

    fun parse(value: String): Instant {
        return Instant.from(formatter.parse(value))
    }
}
