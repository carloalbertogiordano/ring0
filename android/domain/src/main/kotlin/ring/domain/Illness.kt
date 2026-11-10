package ring.domain

enum class WarningLevel { NONE, WATCH, ALERT }

/** Scostamenti notturni dalla baseline personale, in z-score (null = dato mancante). */
data class NightSignals(val skinTempZ: Double?, val restingHrZ: Double?, val hrvZ: Double?)

data class IllnessAssessment(val level: WarningLevel, val reasons: List<String>)

/**
 * Allarme precoce di malessere: scatta solo se piu' segnali sono concordi (temperatura cutanea notturna su,
 * frequenza a riposo su, HRV giu') e per piu' notti, per ridurre i falsi allarmi della singola soglia.
 * Non e' una diagnosi.
 */
object IllnessWarning {
    private const val Z_LIMIT = 1.5
    private const val SIGNALS_NEEDED = 2

    private fun reasons(n: NightSignals): List<String> = buildList {
        if ((n.skinTempZ ?: 0.0) >= Z_LIMIT) add("temperatura notturna sopra la tua norma")
        if ((n.restingHrZ ?: 0.0) >= Z_LIMIT) add("frequenza a riposo piu' alta del solito")
        if ((n.hrvZ ?: 0.0) <= -Z_LIMIT) add("HRV piu' bassa del solito")
    }

    /** `nights` in ordine cronologico, l'ultima e' la piu' recente. */
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
