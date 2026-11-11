package ring.domain

/**
 * Whether the time spent in each sleep stage is in the range typical of healthy adults. Shares are of the time asleep. The ranges come from
 * the sleep-stage norms across the lifespan (Ohayon et al. 2004, Sleep 27(7): deep sleep about 13-23% in young adults and falling with age,
 * REM about 18-28%, light sleep N1+N2 about 45-60%, shown but not judged). Individual variation is large and the ring estimates its
 * stages (agreement with a sleep lab is only moderate), so this is a rough guide, not a finding.
 */
object SleepStageCheck {
    enum class Stage { LIGHT, DEEP, REM }

    /** Light sleep is UNJUDGED: the ring never reports being awake, so every minute of the window is a stage and wakefulness ends up in light. */
    enum class Verdict { LOW, OK, HIGH, UNJUDGED }

    data class Assessment(val stage: Stage, val minutes: Int, val share: Double, val typicalPercent: ClosedFloatingPointRange<Double>, val verdict: Verdict)

    private const val MIN_SLEEP_MIN = 180
    private const val PERCENT = 100.0
    private const val MIDDLE_AGE = 40
    private const val OLDER_AGE = 60
    private val LIGHT_RANGE = 45.0..60.0
    private val REM_RANGE = 18.0..28.0
    private val DEEP_YOUNG = 13.0..23.0
    private val DEEP_MIDDLE = 8.0..18.0
    private val DEEP_OLDER = 5.0..15.0

    /** Null for a short sleep (a nap has a different structure). */
    fun assess(lightMin: Int, deepMin: Int, remMin: Int, ageYears: Int): List<Assessment>? {
        val asleep = lightMin + deepMin + remMin
        if (asleep < MIN_SLEEP_MIN) return null
        val deepRange = when {
            ageYears >= OLDER_AGE -> DEEP_OLDER
            ageYears >= MIDDLE_AGE -> DEEP_MIDDLE
            else -> DEEP_YOUNG
        }
        return listOf(
            Assessment(Stage.LIGHT, lightMin, lightMin.toDouble() / asleep, LIGHT_RANGE, Verdict.UNJUDGED),
            assessment(Stage.DEEP, deepMin, asleep, deepRange),
            assessment(Stage.REM, remMin, asleep, REM_RANGE),
        )
    }

    private fun assessment(stage: Stage, minutes: Int, asleep: Int, range: ClosedFloatingPointRange<Double>): Assessment {
        val share = minutes.toDouble() / asleep
        val percent = share * PERCENT
        val verdict = when {
            percent < range.start -> Verdict.LOW
            percent > range.endInclusive -> Verdict.HIGH
            else -> Verdict.OK
        }
        return Assessment(stage, minutes, share, range, verdict)
    }
}
