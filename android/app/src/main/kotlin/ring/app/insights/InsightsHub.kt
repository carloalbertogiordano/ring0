package ring.app.insights

import ring.domain.BatteryEstimate
import ring.domain.CycleEstimate
import ring.domain.Profile
import ring.domain.RecoveryEvent
import ring.domain.Sex
import ring.domain.Vo2Estimate
import java.time.Instant

/** Everything derived from the stored data that the screens show. Each part is null or empty when it cannot be computed. */
data class InsightsSnapshot(
    val readiness: ReadinessReport? = null,
    val training: TrainingReport? = null,
    val bodyBattery: BodyBatteryReport? = null,
    val sleep: SleepReport? = null,
    val batteryEstimate: BatteryEstimate? = null,
    val fitness: Vo2Estimate? = null,
    val recovery: RecoveryEvent? = null,
    val apnea: ApneaReport? = null,
    val habits: List<HabitRow> = emptyList(),
    /** Only when the user turned the optional cycle estimate on (and the profile is female). */
    val cycle: CycleEstimate? = null,
)

/** Computes the whole snapshot; a failure in one estimate leaves only that part empty. */
class InsightsHub(
    private val insights: Insights,
    private val sleepInsights: SleepInsights,
    private val estimates: EstimateInsights,
) {
    suspend fun compute(profile: Profile?, sleepTargetMin: Int, cycleEnabled: Boolean, now: Long = Instant.now().epochSecond): InsightsSnapshot {
        val resting = runCatching { insights.restingBaseline(now) }.getOrNull()?.toDouble()
        return InsightsSnapshot(
            readiness = runCatching { insights.readiness(now) }.getOrNull(),
            training = profile?.let { runCatching { insights.training(it, now) }.getOrNull() },
            bodyBattery = runCatching { insights.bodyBattery(profile, now) }.getOrNull(),
            sleep = runCatching { sleepInsights.report(sleepTargetMin, resting, now) }.getOrNull(),
            batteryEstimate = runCatching { insights.batteryForecast(now) }.getOrNull(),
            fitness = profile?.let { estimates.fitness(it, resting) },
            recovery = profile?.let { runCatching { estimates.recovery(it, now) }.getOrNull() },
            apnea = runCatching { estimates.apnea(now) }.getOrNull(),
            habits = runCatching { estimates.habits(now) }.getOrNull().orEmpty(),
            cycle = if (cycleEnabled && profile?.sex == Sex.FEMALE) runCatching { estimates.cycle(now) }.getOrNull() else null,
        )
    }
}
