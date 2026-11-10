package ring.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ring.protocol.commands.SleepStage
import ring.protocol.commands.SleepStageType

/** Synthetic RR series: 800 ms base modulated by breathing at `freq` Hz (RSA) plus deterministic noise. */
private fun synthRr(freq: Double, seconds: Double, amp: Double = 50.0, base: Double = 800.0, noise: Double = 6.0): List<Int> {
    val out = mutableListOf<Int>()
    var t = 0.0
    var k = 0
    while (t < seconds) {
        val rr = base + amp * sin(2 * PI * freq * t) + noise * sin(k * 12.9898)
        out += rr.roundToInt()
        t += rr / 1000
        k++
    }
    return out
}

class StatsTest {
    @Test fun `median percentile and robust sd`() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)))
        assertEquals(2.5, Stats.median(listOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(1.0, Stats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.0))
        assertEquals(4.0, Stats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 75.0))
        assertEquals(0.0, Stats.sd(listOf(7.0)))
        assertEquals(1.4826 * 1.0, Stats.robustSd(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-9)
    }

    @Test fun `baseline gives z scores only with enough days`() {
        assertNull(Baseline.of(listOf(40.0, 41.0, 39.0))!!.z(45.0))
        val base = Baseline.of(List(20) { 40.0 + (it % 5) - 2 })!!
        assertEquals(40.0, base.center, 1e-9)
        assertTrue(base.z(45.0)!! > 1.5)
        assertNull(Baseline.of(emptyList()))
    }

    @Test fun `baseline uses only the most recent window and a minimum spread`() {
        val values = List(30) { 100.0 } + List(28) { 50.0 }
        assertEquals(50.0, Baseline.of(values, window = 28)!!.center)
        assertTrue(Baseline.of(List(10) { 5.0 }, minSpread = 0.5)!!.spread >= 0.5)
    }
}

class RespirationTest {
    @Test fun `recovers 15 breaths per minute`() {
        val est = assertNotNull(RespirationRate.fromRr(synthRr(0.25, 120.0)))
        assertEquals(15.0, est.breathsPerMinute, 1.0)
        assertTrue(est.confidence > 0.4, "confidence ${est.confidence}")
    }

    @Test fun `recovers a slow 9 breaths per minute`() {
        assertEquals(9.0, assertNotNull(RespirationRate.fromRr(synthRr(0.15, 150.0))).breathsPerMinute, 1.0)
    }

    @Test fun `recovers a fast 24 breaths per minute`() {
        assertEquals(24.0, assertNotNull(RespirationRate.fromRr(synthRr(0.40, 100.0))).breathsPerMinute, 1.5)
    }

    @Test fun `too short or flat series give no estimate`() {
        assertNull(RespirationRate.fromRr(synthRr(0.25, 20.0)))
        assertNull(RespirationRate.fromRr(List(120) { 800 }))
        assertNull(RespirationRate.fromRr(emptyList()))
    }

    @Test fun `isolated artifacts do not move the estimate`() {
        val rr = synthRr(0.25, 120.0).toMutableList()
        rr[20] = 3000
        rr[55] = 120
        assertEquals(15.0, assertNotNull(RespirationRate.fromRr(rr)).breathsPerMinute, 1.5)
    }
}

class ReadinessTest {
    private val rmssdHistory = List(20) { 40.0 + (it % 5) - 2 }
    private val rhrHistory = List(20) { 58.0 + (it % 3) - 1 }

    @Test fun `great recovery scores high`() {
        val r = assertNotNull(ReadinessScore.compute(ReadinessInput(55.0, rmssdHistory, 54.0, rhrHistory, 490, 0.93)))
        assertTrue(r.score >= 75, "score ${r.score}")
        assertEquals(ReadinessLevel.HIGH, r.level)
        assertEquals(setOf(ReadinessComponent.HRV, ReadinessComponent.RESTING_HR, ReadinessComponent.SLEEP), r.components.keys)
        assertEquals(1.0, r.confidence)
    }

    @Test fun `poor recovery scores low`() {
        val r = assertNotNull(ReadinessScore.compute(ReadinessInput(25.0, rmssdHistory, 68.0, rhrHistory, 280, 0.7)))
        assertTrue(r.score < 35, "score ${r.score}")
        assertEquals(ReadinessLevel.LOW, r.level)
    }

    @Test fun `typical day lands in the middle`() {
        val r = assertNotNull(ReadinessScore.compute(ReadinessInput(40.0, rmssdHistory, 58.0, rhrHistory, 420, 0.88)))
        assertTrue(r.score in 55..80, "score ${r.score}")
    }

    @Test fun `missing signals renormalize and no data gives null`() {
        val onlySleep = assertNotNull(ReadinessScore.compute(ReadinessInput(null, emptyList(), null, emptyList(), 480, null)))
        assertEquals(100, onlySleep.score)
        assertNull(ReadinessScore.compute(ReadinessInput(null, emptyList(), null, emptyList(), null, null)))
    }

    @Test fun `short baseline excludes hrv and lowers confidence`() {
        val r = assertNotNull(ReadinessScore.compute(ReadinessInput(55.0, listOf(40.0, 41.0, 39.0), null, emptyList(), 480, 0.9)))
        assertTrue(ReadinessComponent.HRV !in r.components)
    }
}

class SleepTest {
    private val night = SleepNight(
        startEpoch = 0, endEpoch = 8 * 3600L,
        stages = listOf(
            SleepStage(SleepStageType.AWAKE, 10), SleepStage(SleepStageType.LIGHT, 200), SleepStage(SleepStageType.DEEP, 80),
            SleepStage(SleepStageType.REM, 90), SleepStage(SleepStageType.AWAKE, 20), SleepStage(SleepStageType.LIGHT, 40),
            SleepStage(SleepStageType.AWAKE, 5),
        ),
    )

    @Test fun `summarizes a night`() {
        val s = SleepMetrics.summarize(night)
        assertEquals(480, s.timeInBedMin)
        assertEquals(410, s.totalSleepMin)
        assertEquals(410.0 / 480, s.efficiency, 1e-9)
        assertEquals(10, s.latencyMin)
        assertEquals(20, s.wasoMin)
        assertEquals(80.0 / 410 * 100, s.deepPct, 1e-9)
        assertEquals(90.0 / 410 * 100, s.remPct, 1e-9)
    }

    @Test fun `empty night is safe`() {
        val s = SleepMetrics.summarize(SleepNight(0, 0, emptyList()))
        assertEquals(0, s.totalSleepMin)
        assertEquals(0.0, s.efficiency)
    }

    @Test fun `sleep debt ignores surplus and uses last nights`() {
        assertEquals(140, SleepMetrics.sleepDebtMin(listOf(400, 480, 500, 420)))
        assertEquals(60, SleepMetrics.sleepDebtMin(listOf(0, 0, 420), nights = 1))
    }

    @Test fun `regularity handles midnight wraparound`() {
        val steady = SleepRegularity.score(listOf(1380, 1390, 1370, 1385))!!
        val erratic = SleepRegularity.score(listOf(1260, 1500 % 1440, 1380, 60))!!
        assertTrue(steady > 85 && erratic < 30, "steady $steady erratic $erratic")
        val acrossMidnight = SleepRegularity.onsetSdMinutes(listOf(1430, 10, 1420, 5))!!
        assertTrue(acrossMidnight < 15, "sd $acrossMidnight")
        assertTrue(abs(((SleepRegularity.meanOnsetMinute(listOf(1430, 10, 1420, 5)) + 720) % 1440) - 720) < 15)
        assertNull(SleepRegularity.score(listOf(1380)))
    }
}

class Spo2Test {
    private fun hourly(vararg v: Double) = v.mapIndexed { i, x -> TimedValue(i * 3600L, x) }

    @Test fun `summary counts samples under threshold`() {
        val s = assertNotNull(NocturnalSpo2.summary(hourly(97.0, 96.0, 91.0, 95.0, 90.0), 92.0))
        assertEquals(90.0, s.min)
        assertEquals(2, s.belowThreshold)
        assertNull(NocturnalSpo2.summary(emptyList()))
    }

    @Test fun `odi counts one event per desaturation`() {
        val r = NocturnalSpo2.odi(hourly(97.0, 97.0, 97.0, 93.0, 97.0, 97.0, 97.0, 93.0, 94.0, 97.0))
        assertEquals(2, r.events)
        assertEquals(9.0, r.hours, 1e-9)
        assertEquals(2 / 9.0, r.indexPerHour, 1e-9)
    }

    @Test fun `flat night has no events and tiny input is safe`() {
        assertEquals(0, NocturnalSpo2.odi(hourly(96.0, 97.0, 96.0, 97.0, 96.0)).events)
        assertEquals(0, NocturnalSpo2.odi(hourly(95.0)).events)
    }
}

class IllnessTest {
    private val normal = NightSignals(0.2, 0.1, -0.3)
    private val flagged = NightSignals(2.0, 1.8, -0.5)

    @Test fun `quiet nights give no warning`() {
        assertEquals(WarningLevel.NONE, IllnessWarning.assess(listOf(normal, normal, normal)).level)
        assertEquals(WarningLevel.NONE, IllnessWarning.assess(emptyList()).level)
    }

    @Test fun `a single concordant night is a watch`() {
        val a = IllnessWarning.assess(listOf(normal, flagged))
        assertEquals(WarningLevel.WATCH, a.level)
        assertEquals(2, a.reasons.size)
    }

    @Test fun `two concordant nights in a row raise an alert`() {
        assertEquals(WarningLevel.ALERT, IllnessWarning.assess(listOf(normal, flagged, flagged)).level)
    }

    @Test fun `a single signal is never enough`() {
        val onlyTemp = NightSignals(3.0, 0.0, 0.0)
        assertEquals(WarningLevel.NONE, IllnessWarning.assess(listOf(onlyTemp)).level)
        assertEquals(WarningLevel.WATCH, IllnessWarning.assess(listOf(onlyTemp, onlyTemp)).level)
    }
}

class ContextAlertsTest {
    private fun hr(vararg v: Double) = v.mapIndexed { i, x -> TimedValue(i * 300L, x) }

    @Test fun `high hr at rest alerts, while moving it does not`() {
        assertEquals(HrAlert.HIGH, ContextAlerts.heartRate(hr(140.0, 142.0), 130.0, 40.0) { true })
        assertNull(ContextAlerts.heartRate(hr(140.0, 142.0), 130.0, 40.0) { false })
    }

    @Test fun `unknown context needs a margin above the threshold`() {
        assertNull(ContextAlerts.heartRate(hr(135.0, 136.0), 130.0, 40.0) { null })
        assertEquals(HrAlert.HIGH, ContextAlerts.heartRate(hr(150.0, 152.0), 130.0, 40.0) { null })
    }

    @Test fun `needs two consecutive samples and low hr alerts`() {
        assertNull(ContextAlerts.heartRate(hr(150.0, 80.0), 130.0, 40.0) { true })
        assertNull(ContextAlerts.heartRate(hr(150.0), 130.0, 40.0) { true })
        assertEquals(HrAlert.LOW, ContextAlerts.heartRate(hr(35.0, 36.0), 130.0, 40.0) { true })
    }

    @Test fun `spo2 needs two low samples or one severe drop`() {
        val now = 10_000L
        fun s(ts: Long, v: Double) = TimedValue(ts, v)
        assertTrue(ContextAlerts.spo2(listOf(s(now - 3600, 90.0), s(now - 100, 91.0)), 92.0, now))
        assertTrue(!ContextAlerts.spo2(listOf(s(now - 100, 91.0)), 92.0, now))
        assertTrue(ContextAlerts.spo2(listOf(s(now - 100, 86.0)), 92.0, now))
        assertTrue(!ContextAlerts.spo2(listOf(s(now - 5 * 3600, 85.0), s(now - 100, 97.0)), 92.0, now))
    }
}

class RestingHrTest {
    @Test fun `uses the low quartile during sleep`() {
        val sleep = (0 until 40).map { TimedValue(1000L + it * 300, 52.0 + (it % 10)) }
        val awake = (0 until 40).map { TimedValue(100_000L + it * 300, 80.0 + (it % 10)) }
        val rhr = assertNotNull(RestingHr.estimate(sleep + awake, 1000L, 1000L + 40 * 300))
        assertTrue(rhr in 52.0..54.5, "rhr $rhr")
    }

    @Test fun `falls back to the day percentile and needs enough samples`() {
        val day = (0 until 100).map { TimedValue(it * 300L, 60.0 + it % 30) }
        assertTrue(assertNotNull(RestingHr.estimate(day)) < 64)
        assertNull(RestingHr.estimate(day.take(10)))
    }
}

class TriageTest {
    private fun input(
        bpm: Int? = 120, restingHr: Double? = 60.0, spo2: Int? = 97, stress: Int? = 30, hrvZ: Double? = 0.0,
        skinTemp: Double? = 36.6, tempZ: Double? = 0.0, moving: Boolean? = null, symptoms: Set<Symptom> = emptySet(),
    ) = TriageInput(bpm, restingHr, spo2, stress, hrvZ, skinTemp, tempZ, moving, symptoms)

    @Test fun `exercise explains a high heart rate`() {
        val r = Triage.assess(input(moving = true))
        assertEquals(listOf(TriageCause.ACTIVITY), r.causes)
        assertEquals(TriageLevel.NORMAL, r.level)
        assertEquals(listOf(TriageAdvice.REST_AND_RECHECK), r.advice)
    }

    @Test fun `high stress or low hrv points to stress`() {
        assertEquals(listOf(TriageCause.STRESS), Triage.assess(input(stress = 82)).causes)
        assertEquals(listOf(TriageCause.STRESS), Triage.assess(input(hrvZ = -2.0)).causes)
        assertEquals(TriageLevel.NORMAL, Triage.assess(input(stress = 82)).level)
    }

    @Test fun `fever signs raise a watch`() {
        val byTemp = Triage.assess(input(skinTemp = 38.1))
        assertTrue(TriageCause.POSSIBLE_ILLNESS in byTemp.causes)
        assertEquals(TriageLevel.WATCH, byTemp.level)
        assertTrue(TriageCause.POSSIBLE_ILLNESS in Triage.assess(input(tempZ = 2.0, hrvZ = -1.2)).causes)
        assertTrue(TriageCause.POSSIBLE_ILLNESS in Triage.assess(input(symptoms = setOf(Symptom.FEVER_OR_CHILLS))).causes)
    }

    @Test fun `low oxygen is a watch and very low oxygen is urgent`() {
        val low = Triage.assess(input(spo2 = 92))
        assertTrue(TriageCause.LOW_OXYGEN in low.causes)
        assertEquals(TriageLevel.WATCH, low.level)
        val urgent = Triage.assess(input(spo2 = 88))
        assertEquals(TriageLevel.URGENT, urgent.level)
        assertEquals(listOf(TriageAdvice.SEEK_MEDICAL_HELP), urgent.advice)
    }

    @Test fun `red flag symptoms are always urgent`() {
        for (s in listOf(Symptom.CHEST_PAIN, Symptom.SHORTNESS_OF_BREATH, Symptom.DIZZINESS_OR_FAINTING)) {
            assertEquals(TriageLevel.URGENT, Triage.assess(input(moving = true, symptoms = setOf(s))).level, "$s")
        }
    }

    @Test fun `an unexplained elevated rate is a watch with advice to recheck`() {
        val r = Triage.assess(input(bpm = 125))
        assertEquals(listOf(TriageCause.UNEXPLAINED), r.causes)
        assertEquals(TriageLevel.WATCH, r.level)
        assertEquals(listOf(TriageAdvice.REST_AND_RECHECK, TriageAdvice.MONITOR), r.advice)
    }

    @Test fun `a normal reading with no findings just monitors`() {
        val r = Triage.assess(input(bpm = 72))
        assertTrue(r.causes.isEmpty())
        assertEquals(TriageLevel.NORMAL, r.level)
        assertEquals(listOf(TriageAdvice.MONITOR), r.advice)
    }

    @Test fun `stimulants and missing data are handled`() {
        assertEquals(listOf(TriageCause.STIMULANT), Triage.assess(input(symptoms = setOf(Symptom.CAFFEINE_OR_STIMULANT))).causes)
        val empty = Triage.assess(TriageInput(null, null, null, null, null, null, null, null, emptySet()))
        assertEquals(TriageLevel.NORMAL, empty.level)
    }
}

class AlarmTimeTest {
    @Test fun `local alarm in winter time converts to utc minus one hour`() {
        val mondayToFriday = 0b0011111
        val ring = AlarmTime.toRing(LocalAlarm(7, 0, mondayToFriday), offsetMinutes = 60)
        assertEquals(6 * 60, ring.minutes)
        assertEquals(0b0111110, ring.weekdays) // Mon..Fri in Sunday-based bits 1..5
    }

    @Test fun `an alarm just after local midnight moves to the previous utc day`() {
        val monday = 0b0000001
        val ring = AlarmTime.toRing(LocalAlarm(0, 30, monday), offsetMinutes = 60)
        assertEquals(23 * 60 + 30, ring.minutes)
        assertEquals(1 shl 0, ring.weekdays) // Sunday in UTC
    }

    @Test fun `an alarm late in the evening can move to the next utc day`() {
        val sunday = 0b1000000
        val ring = AlarmTime.toRing(LocalAlarm(23, 30, sunday), offsetMinutes = -120)
        assertEquals(1 * 60 + 30, ring.minutes)
        assertEquals(1 shl 1, ring.weekdays) // Monday in UTC
    }

    @Test fun `round trip local to ring and back`() {
        for (offset in listOf(0, 60, 120, -300, 330)) {
            for (alarm in listOf(LocalAlarm(7, 0, 0b0011111), LocalAlarm(0, 15, 0b1000001), LocalAlarm(23, 45, 0b0101010), LocalAlarm(12, 0, 0))) {
                assertEquals(alarm, AlarmTime.toLocal(AlarmTime.toRing(alarm, offset), offset), "offset $offset $alarm")
            }
        }
    }
}
