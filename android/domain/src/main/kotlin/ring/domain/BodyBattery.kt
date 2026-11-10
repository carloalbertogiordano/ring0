package ring.domain

/** One half-hour of the day for the energy model. */
data class EnergySlot(
    val ts: Long,
    /** 0..1: how much of the slot was spent asleep, weighted by sleep quality. */
    val sleepWeight: Double,
    /** 0..100 stress level of the slot, null when unknown. */
    val stress: Double?,
    /** Training load (TRIMP) accumulated in the slot. */
    val trimp: Double,
)

/**
 * "Body battery": a 0-100 energy reserve that rises with sleep and calm and falls with stress and effort.
 * This is our own transparent heuristic (the commercial algorithms are not public), tuned so that a normal day drains
 * roughly 50-60 points and a night of 8 hours restores them. It is a guide to your own pattern, not a measurement.
 */
object BodyBattery {
    private const val SLOT_HOURS = 0.5
    private const val SLEEP_RECHARGE_PER_HOUR = 12.0
    private const val BASE_DRAIN_PER_HOUR = 1.2
    private const val STRESS_DRAIN_PER_HOUR_AT_100 = 3.0
    private const val CALM_RECHARGE_PER_HOUR = 1.8
    private const val CALM_BELOW_STRESS = 30.0
    private const val TRIMP_DRAIN = 0.25
    private const val UNKNOWN_STRESS = 40.0
    private const val MAX_LEVEL = 100.0
    private const val STRESS_SCALE = 100.0

    private const val LIGHT_SLEEP_QUALITY = 0.7

    /** 0..1 share of the time in bed that counts as restorative: deep and REM fully, light sleep partly, awake not at all. */
    fun sleepQuality(lightMin: Int, deepMin: Int, remMin: Int, inBedMin: Int): Double =
        if (inBedMin <= 0) 0.0 else ((LIGHT_SLEEP_QUALITY * lightMin + deepMin + remMin) / inBedMin).coerceIn(0.0, 1.0)

    /** Level after each slot, starting from `start`. */
    fun simulate(slots: List<EnergySlot>, start: Double): List<TimedValue> {
        var level = start
        return slots.map { slot ->
            level = (level + delta(slot)).coerceIn(0.0, MAX_LEVEL)
            TimedValue(slot.ts, level)
        }
    }

    private fun delta(slot: EnergySlot): Double {
        val stress = slot.stress ?: UNKNOWN_STRESS
        val awake = 1 - slot.sleepWeight
        val drain = BASE_DRAIN_PER_HOUR + STRESS_DRAIN_PER_HOUR_AT_100 * stress / STRESS_SCALE
        val calm = if (stress < CALM_BELOW_STRESS) CALM_RECHARGE_PER_HOUR else 0.0
        return (slot.sleepWeight * SLEEP_RECHARGE_PER_HOUR + awake * (calm - drain)) * SLOT_HOURS - slot.trimp * TRIMP_DRAIN
    }
}
