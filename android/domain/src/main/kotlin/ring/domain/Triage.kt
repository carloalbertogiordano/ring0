package ring.domain

enum class TriageLevel { NORMAL, WATCH, URGENT }

/** Why the heart rate (or another vital) may be off; the UI turns these into localized text. */
enum class TriageCause { ACTIVITY, STRESS, POSSIBLE_ILLNESS, LOW_OXYGEN, STIMULANT, UNEXPLAINED }

enum class TriageAdvice { REST_AND_RECHECK, MONITOR, SEEK_MEDICAL_HELP }

enum class Symptom { CHEST_PAIN, SHORTNESS_OF_BREATH, DIZZINESS_OR_FAINTING, PALPITATIONS, EXERCISED, CAFFEINE_OR_STIMULANT, FEVER_OR_CHILLS }

/** What is known at the moment of a check. Null means "not measured / not available". */
data class TriageInput(
    val bpm: Int?,
    val restingHr: Double?,
    val spo2: Int?,
    val stress: Int?,
    /** z-score of the current HRV against the personal baseline (negative = lower than usual). */
    val hrvZ: Double?,
    val skinTemp: Double?,
    /** z-score of the skin temperature against the personal baseline. */
    val tempZ: Double?,
    /** True/false when step data says so; null when unknown. */
    val moving: Boolean?,
    val symptoms: Set<Symptom>,
)

data class TriageResult(val level: TriageLevel, val causes: List<TriageCause>, val advice: List<TriageAdvice>)

/**
 * Rule-based check for "why is my heart rate up?". It combines the measurements taken right now with the
 * recent context and the symptoms the user reports. Conservative by design: any red-flag symptom or a very
 * low SpO2 always ends in "seek medical help". Not a diagnosis.
 */
object Triage {
    private const val SPO2_URGENT = 90
    private const val SPO2_LOW = 94
    private const val STRESS_HIGH = 70
    private const val Z_LIMIT = 1.5
    private const val Z_SOFT = 1.0
    private const val FEVER_C = 37.8
    private const val ELEVATED_OVER_RESTING = 25.0
    private val RED_FLAGS = setOf(Symptom.CHEST_PAIN, Symptom.SHORTNESS_OF_BREATH, Symptom.DIZZINESS_OR_FAINTING)

    fun assess(input: TriageInput): TriageResult {
        val urgent = (input.spo2 != null && input.spo2 < SPO2_URGENT) || input.symptoms.any { it in RED_FLAGS }
        val causes = buildList {
            if (input.moving == true || Symptom.EXERCISED in input.symptoms) add(TriageCause.ACTIVITY)
            if ((input.stress ?: 0) >= STRESS_HIGH || (input.hrvZ ?: 0.0) <= -Z_LIMIT) add(TriageCause.STRESS)
            val feverish = Symptom.FEVER_OR_CHILLS in input.symptoms || (input.skinTemp ?: 0.0) >= FEVER_C ||
                ((input.tempZ ?: 0.0) >= Z_LIMIT && (input.hrvZ ?: 0.0) <= -Z_SOFT)
            if (feverish) add(TriageCause.POSSIBLE_ILLNESS)
            if (input.spo2 != null && input.spo2 in SPO2_URGENT until SPO2_LOW) add(TriageCause.LOW_OXYGEN)
            if (Symptom.CAFFEINE_OR_STIMULANT in input.symptoms) add(TriageCause.STIMULANT)
        }
        val elevated = input.bpm != null && input.restingHr != null && input.bpm > input.restingHr + ELEVATED_OVER_RESTING
        val finalCauses = if (causes.isEmpty() && elevated) listOf(TriageCause.UNEXPLAINED) else causes
        val watch = finalCauses.any { it == TriageCause.LOW_OXYGEN || it == TriageCause.POSSIBLE_ILLNESS || it == TriageCause.UNEXPLAINED }
        val level = when {
            urgent -> TriageLevel.URGENT
            watch -> TriageLevel.WATCH
            else -> TriageLevel.NORMAL
        }
        val advice = when (level) {
            TriageLevel.URGENT -> listOf(TriageAdvice.SEEK_MEDICAL_HELP)
            TriageLevel.WATCH -> listOf(TriageAdvice.REST_AND_RECHECK, TriageAdvice.MONITOR)
            TriageLevel.NORMAL -> if (elevated) listOf(TriageAdvice.REST_AND_RECHECK) else listOf(TriageAdvice.MONITOR)
        }
        return TriageResult(level, finalCauses, advice)
    }
}
