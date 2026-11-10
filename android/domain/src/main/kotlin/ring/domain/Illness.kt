package ring.domain

enum class WarningLevel { NONE, WATCH, ALERT }

/** Why a night was flagged; the UI turns these into localized text. */
enum class IllnessReason { SKIN_TEMP_HIGH, RESTING_HR_HIGH, HRV_LOW }

/** Nightly deviations from the personal baseline, as z-scores (null = missing data). */
data class NightSignals(val skinTempZ: Double?, val restingHrZ: Double?, val hrvZ: Double?)

data class IllnessAssessment(val level: WarningLevel, val reasons: List<IllnessReason>)

/**
 * Early illness warning: fires only when several signals agree (higher nocturnal skin temperature, higher
 * resting heart rate, lower HRV) and over more than one night, to cut the false alarms of a single threshold.
 * Not a diagnosis.
 */
object IllnessWarning {
    private const val Z_LIMIT = 1.5
    private const val SIGNALS_NEEDED = 2

    private fun reasons(n: NightSignals): List<IllnessReason> = buildList {
        if ((n.skinTempZ ?: 0.0) >= Z_LIMIT) add(IllnessReason.SKIN_TEMP_HIGH)
        if ((n.restingHrZ ?: 0.0) >= Z_LIMIT) add(IllnessReason.RESTING_HR_HIGH)
        if ((n.hrvZ ?: 0.0) <= -Z_LIMIT) add(IllnessReason.HRV_LOW)
    }

    /** `nights` in chronological order, the last one being the most recent. */
    fun assess(nights: List<NightSignals>): IllnessAssessment {
        val recent = nights.takeLast(2).map(::reasons)
        val last = recent.lastOrNull().orEmpty()
        val previous = if (recent.size == 2) recent.first() else emptyList()
        val flaggedLast = last.size >= SIGNALS_NEEDED
        val flaggedPrevious = previous.size >= SIGNALS_NEEDED
        return when {
            flaggedLast && flaggedPrevious -> IllnessAssessment(WarningLevel.ALERT, last)
            flaggedLast || (last.isNotEmpty() && previous.isNotEmpty()) -> IllnessAssessment(WarningLevel.WATCH, last.ifEmpty { previous })
            else -> IllnessAssessment(WarningLevel.NONE, emptyList())
        }
    }
}
