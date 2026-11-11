package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val MINUTE_5 = 300L

private fun hr(vararg values: Double): List<TimedValue> = values.mapIndexed { i, v -> TimedValue(i * MINUTE_5, v) }

class HeartRateModelTest {
    private val man = Profile(ageYears = 30, sex = Sex.MALE, weightKg = 80.0, heightCm = 180.0)

    @Test fun `max heart rate follows Tanaka when no override is set`() {
        assertEquals(187, HeartRateModel.hrMax(man))
        assertEquals(180, HeartRateModel.hrMax(man.copy(ageYears = 40)))
    }

    @Test fun `a measured max heart rate wins over the formula`() {
        assertEquals(195, HeartRateModel.hrMax(man.copy(hrMaxOverride = 195)))
    }

    @Test fun `zones are percentages of max heart rate and rest is below 50 percent`() {
        val max = 190
        assertEquals(HrZone.REST, HeartRateModel.zone(94.0, max))
        assertEquals(HrZone.Z1, HeartRateModel.zone(95.0, max))
        assertEquals(HrZone.Z2, HeartRateModel.zone(115.0, max))
        assertEquals(HrZone.Z3, HeartRateModel.zone(135.0, max))
        assertEquals(HrZone.Z4, HeartRateModel.zone(160.0, max))
        assertEquals(HrZone.Z5, HeartRateModel.zone(175.0, max))
        assertEquals(HrZone.Z5, HeartRateModel.zone(210.0, max))
    }

    @Test fun `minutes per zone count each sample as the sampling interval`() {
        val minutes = HeartRateModel.minutesPerZone(hr(60.0, 100.0, 100.0, 175.0), sampleMinutes = 5, hrMax = 190)
        assertEquals(5, minutes[HrZone.REST])
        assertEquals(10, minutes[HrZone.Z1])
        assertEquals(5, minutes[HrZone.Z5])
        assertEquals(0, minutes[HrZone.Z3])
    }

    @Test fun `zero readings are not counted as wear time`() {
        val minutes = HeartRateModel.minutesPerZone(hr(0.0, 100.0), sampleMinutes = 5, hrMax = 190)
        assertEquals(0, minutes[HrZone.REST])
        assertEquals(5, minutes[HrZone.Z1])
    }
}

class TrimpTest {
    @Test fun `banister trimp for a 30 minute session matches the hand computed value`() {
        // ratio (150-60)/(190-60) = 0.6923; 30 * ratio * 0.64 * e^(1.92 * ratio)
        assertEquals(50.22, Trimp.session(avgHr = 150.0, minutes = 30.0, hrRest = 60, hrMax = 190, sex = Sex.MALE), 0.01)
        assertEquals(56.76, Trimp.session(avgHr = 150.0, minutes = 30.0, hrRest = 60, hrMax = 190, sex = Sex.FEMALE), 0.01)
    }

    @Test fun `resting heart rate and below contribute nothing`() {
        assertEquals(0.0, Trimp.session(avgHr = 60.0, minutes = 60.0, hrRest = 60, hrMax = 190, sex = Sex.MALE))
        assertEquals(0.0, Trimp.session(avgHr = 50.0, minutes = 60.0, hrRest = 60, hrMax = 190, sex = Sex.MALE))
    }

    @Test fun `ratio is capped at one`() {
        val capped = Trimp.session(avgHr = 250.0, minutes = 10.0, hrRest = 60, hrMax = 190, sex = Sex.MALE)
        val atMax = Trimp.session(avgHr = 190.0, minutes = 10.0, hrRest = 60, hrMax = 190, sex = Sex.MALE)
        assertEquals(atMax, capped, 1e-9)
    }

    @Test fun `a day of samples sums the per sample load`() {
        val samples = hr(60.0, 150.0, 150.0, 0.0)
        val expected = 2 * Trimp.session(150.0, 5.0, 60, 190, Sex.MALE)
        assertEquals(expected, Trimp.fromSamples(samples, sampleMinutes = 5, hrRest = 60, hrMax = 190, sex = Sex.MALE), 1e-9)
    }

    @Test fun `a day spent sitting is not training load`() {
        // 14 hours at 70-90 bpm with a resting rate of 55: every sample is above rest, none is effort (below 50% of the maximum)
        val sitting = hr(*DoubleArray(168) { 70.0 + it % 21 })
        assertEquals(0.0, Trimp.fromSamples(sitting, sampleMinutes = 5, hrRest = 55, hrMax = 195, sex = Sex.MALE))
    }

    @Test fun `light effort from half of the maximum heart rate counts`() {
        val walking = hr(100.0, 100.0)
        assertEquals(
            2 * Trimp.session(100.0, 5.0, 55, 195, Sex.MALE),
            Trimp.fromSamples(walking, sampleMinutes = 5, hrRest = 55, hrMax = 195, sex = Sex.MALE),
            1e-9,
        )
    }
}

class LoadTrendTest {
    @Test fun `too little history gives no trend`() {
        assertNull(LoadTrend.compute(List(13) { 50.0 }))
    }

    @Test fun `steady load is in the usual range`() {
        val trend = LoadTrend.compute(List(28) { 50.0 })!!
        assertEquals(1.0, trend.ratio, 1e-9)
        assertEquals(LoadLevel.USUAL, trend.level)
    }

    @Test fun `a hard recent week reads as high and an easy one as low`() {
        val high = LoadTrend.compute(List(21) { 40.0 } + List(7) { 90.0 })!!
        assertEquals(LoadLevel.HIGH, high.level)
        val low = LoadTrend.compute(List(21) { 60.0 } + List(7) { 20.0 })!!
        assertEquals(LoadLevel.LOW, low.level)
    }

    @Test fun `acute window is the last 7 days and chronic is the last 28 at most`() {
        val trend = LoadTrend.compute(List(40) { 10.0 } + List(7) { 70.0 })!!
        assertEquals(70.0, trend.acute, 1e-9)
        assertEquals((21 * 10.0 + 7 * 70.0) / 28, trend.chronic, 1e-9)
    }

    @Test fun `no load at all gives no ratio`() {
        assertNull(LoadTrend.compute(List(28) { 0.0 }))
    }
}

class EnergyTest {
    private val man = Profile(ageYears = 30, sex = Sex.MALE, weightKg = 80.0, heightCm = 180.0)
    private val woman = Profile(ageYears = 30, sex = Sex.FEMALE, weightKg = 60.0, heightCm = 165.0)

    @Test fun `basal metabolic rate follows Mifflin St Jeor`() {
        assertEquals(1780.0, Energy.bmrKcalPerDay(man), 1e-9)
        assertEquals(1320.25, Energy.bmrKcalPerDay(woman), 1e-9)
    }

    @Test fun `heart rate energy follows Keytel and converts kJ to kcal`() {
        assertEquals(14.70, Energy.keytelKcalPerMin(150.0, man), 0.01)
        assertEquals(9.88, Energy.keytelKcalPerMin(150.0, woman), 0.01)
    }

    @Test fun `keytel never goes below zero at very low heart rates`() {
        assertEquals(0.0, Energy.keytelKcalPerMin(30.0, woman))
    }

    @Test fun `active kcal only counts samples above the exercise threshold and removes the basal part`() {
        val hrMax = HeartRateModel.hrMax(man) // 187, threshold 60% = 112.2
        val samples = hr(70.0, 150.0, 150.0, 100.0)
        val perMinute = Energy.keytelKcalPerMin(150.0, man) - Energy.bmrKcalPerDay(man) / 1440
        assertEquals(2 * 5 * perMinute, Energy.activeKcal(samples, sampleMinutes = 5, profile = man, hrMax = hrMax), 1e-9)
    }

    @Test fun `total is basal for the day plus active`() {
        val samples = hr(150.0)
        val active = Energy.activeKcal(samples, 5, man, 187)
        assertEquals(1780.0 + active, Energy.totalKcal(samples, 5, man, 187), 1e-9)
    }
}

class TrainingSummaryTest {
    private val man = Profile(ageYears = 30, sex = Sex.MALE, weightKg = 80.0, heightCm = 180.0)

    @Test fun `a day combines zones, load and active energy`() {
        val samples = hr(60.0, 150.0, 150.0, 100.0, 0.0)
        val day = TrainingSummary.day(samples, sampleMinutes = 5, profile = man, hrRest = 60)
        assertEquals(5, day.zoneMinutes[HrZone.REST])
        assertEquals(5, day.zoneMinutes[HrZone.Z1])
        assertEquals(10, day.zoneMinutes[HrZone.Z4])
        val expectedTrimp = 2 * Trimp.session(150.0, 5.0, 60, 187, Sex.MALE) + Trimp.session(100.0, 5.0, 60, 187, Sex.MALE)
        assertEquals(expectedTrimp, day.trimp, 1e-9)
        assertEquals(Energy.activeKcal(samples, 5, man, 187), day.activeKcal, 1e-9)
    }

    @Test fun `a day without data is all zero`() {
        val day = TrainingSummary.day(emptyList(), sampleMinutes = 5, profile = man, hrRest = 60)
        assertEquals(0.0, day.trimp)
        assertEquals(0.0, day.activeKcal)
        assertEquals(0, day.zoneMinutes.values.sum())
    }

    @Test fun `basal energy is spread evenly over the day`() {
        assertEquals(890.0, Energy.basalKcal(man, minutes = 720), 1e-9)
    }
}
