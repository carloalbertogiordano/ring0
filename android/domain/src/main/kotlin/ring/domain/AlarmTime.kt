package ring.domain

/** An alarm as the user thinks of it: local time and local weekdays (bit 0 = Monday ... bit 6 = Sunday; 0 = once). */
data class LocalAlarm(val hour: Int, val minute: Int, val weekdays: Int)

/** The same alarm in ring time (UTC): minutes since midnight and weekdays with bit 0 = Sunday ... bit 6 = Saturday. */
data class RingTimeAlarm(val minutes: Int, val weekdays: Int)

/** Converts alarms between the user's local time and the ring's UTC clock, moving weekdays when the day changes. */
object AlarmTime {
    private const val MINUTES_PER_DAY = 1440
    private const val DAYS = 7

    /** `offsetMinutes` = local minus UTC (e.g. +60 for CET). */
    fun toRing(local: LocalAlarm, offsetMinutes: Int): RingTimeAlarm {
        val total = local.hour * 60 + local.minute - offsetMinutes
        val dayShift = Math.floorDiv(total, MINUTES_PER_DAY)
        val minutes = Math.floorMod(total, MINUTES_PER_DAY)
        var mask = 0
        for (d in 0 until DAYS) {
            if (local.weekdays and (1 shl d) == 0) continue
            val sundayBased = (d + 1) % DAYS // Monday=1 ... Saturday=6, Sunday=0
            mask = mask or (1 shl Math.floorMod(sundayBased + dayShift, DAYS))
        }
        return RingTimeAlarm(minutes, mask)
    }

    fun toLocal(ring: RingTimeAlarm, offsetMinutes: Int): LocalAlarm {
        val total = ring.minutes + offsetMinutes
        val dayShift = Math.floorDiv(total, MINUTES_PER_DAY)
        val minutes = Math.floorMod(total, MINUTES_PER_DAY)
        var mask = 0
        for (d in 0 until DAYS) {
            if (ring.weekdays and (1 shl d) == 0) continue
            val localSundayBased = Math.floorMod(d + dayShift, DAYS)
            mask = mask or (1 shl Math.floorMod(localSundayBased - 1, DAYS)) // back to Monday=0
        }
        return LocalAlarm(minutes / 60, minutes % 60, mask)
    }
}
