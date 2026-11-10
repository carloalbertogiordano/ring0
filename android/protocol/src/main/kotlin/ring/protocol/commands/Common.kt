package ring.protocol.commands

import java.time.Instant

/** A measurement: UTC instant and value. */
data class Reading(val time: Instant, val value: Double)

internal const val MINUTES_PER_DAY = 24 * 60
internal const val SECONDS_PER_MINUTE = 60L

internal fun Instant.plusMinutes(minutes: Int): Instant = plusSeconds(minutes * SECONDS_PER_MINUTE)
