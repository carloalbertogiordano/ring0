package ring.domain

/**
 * Energy estimates. Basal: Mifflin-St Jeor 1990. Exercise: Keytel 2005 heart rate equations, used only above
 * 60% of max heart rate (below it the basal rate applies). Both are population equations: expect errors of 10% or more.
 */
object Energy {
    private const val MIFFLIN_WEIGHT = 10.0
    private const val MIFFLIN_HEIGHT = 6.25
    private const val MIFFLIN_AGE = 5.0
    private const val MIFFLIN_MALE_OFFSET = 5.0
    private const val MIFFLIN_FEMALE_OFFSET = -161.0
    private const val KJ_PER_KCAL = 4.184
    private const val MINUTES_PER_DAY = 1440.0
    private const val EXERCISE_FRACTION_OF_MAX = 0.6

    fun bmrKcalPerDay(p: Profile): Double =
        MIFFLIN_WEIGHT * p.weightKg + MIFFLIN_HEIGHT * p.heightCm - MIFFLIN_AGE * p.ageYears +
            if (p.sex == Sex.MALE) MIFFLIN_MALE_OFFSET else MIFFLIN_FEMALE_OFFSET

    /** Gross energy per minute from heart rate (Keytel 2005, kJ/min converted to kcal/min), never negative. */
    fun keytelKcalPerMin(hr: Double, p: Profile): Double {
        val kj = if (p.sex == Sex.MALE) {
            -55.0969 + 0.6309 * hr + 0.1988 * p.weightKg + 0.2017 * p.ageYears
        } else {
            -20.4022 + 0.4472 * hr - 0.1263 * p.weightKg + 0.074 * p.ageYears
        }
        return (kj / KJ_PER_KCAL).coerceAtLeast(0.0)
    }

    /** Energy above the basal rate during the samples at or over 60% of max heart rate. */
    fun activeKcal(samples: List<TimedValue>, sampleMinutes: Int, profile: Profile, hrMax: Int): Double {
        val basalPerMinute = bmrKcalPerDay(profile) / MINUTES_PER_DAY
        return samples
            .filter { it.value >= EXERCISE_FRACTION_OF_MAX * hrMax }
            .sumOf { sampleMinutes * (keytelKcalPerMin(it.value, profile) - basalPerMinute).coerceAtLeast(0.0) }
    }

    fun totalKcal(samples: List<TimedValue>, sampleMinutes: Int, profile: Profile, hrMax: Int): Double =
        bmrKcalPerDay(profile) + activeKcal(samples, sampleMinutes, profile, hrMax)
}
