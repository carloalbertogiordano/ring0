package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepStageCheckTest {
    private fun byStage(light: Int, deep: Int, rem: Int, age: Int = 25) =
        SleepStageCheck.assess(lightMin = light, deepMin = deep, remMin = rem, ageYears = age)!!.associateBy { it.stage }

    @Test fun `shares are of the time asleep, awake time is not part of them`() {
        val r = byStage(light = 240, deep = 90, rem = 90) // 420 min asleep
        assertEquals(240.0 / 420, r.getValue(SleepStageCheck.Stage.LIGHT).share, 1e-9)
        assertEquals(90.0 / 420, r.getValue(SleepStageCheck.Stage.DEEP).share, 1e-9)
    }

    @Test fun `a typical night is fine in the judged stages`() {
        val r = byStage(light = 230, deep = 90, rem = 100) // 55 / 21 / 24 %
        assertEquals(SleepStageCheck.Verdict.OK, r.getValue(SleepStageCheck.Stage.DEEP).verdict)
        assertEquals(SleepStageCheck.Verdict.OK, r.getValue(SleepStageCheck.Stage.REM).verdict)
    }

    @Test fun `light sleep is shown but never judged because it is the remainder and absorbs unreported wakefulness`() {
        val r = byStage(light = 330, deep = 30, rem = 60) // 79 % light
        assertEquals(SleepStageCheck.Verdict.UNJUDGED, r.getValue(SleepStageCheck.Stage.LIGHT).verdict)
        assertEquals(330, r.getValue(SleepStageCheck.Stage.LIGHT).minutes)
    }

    @Test fun `too little deep sleep and too little rem are called out`() {
        val r = byStage(light = 330, deep = 30, rem = 60) // 79 / 7 / 14 %
        assertEquals(SleepStageCheck.Verdict.LOW, r.getValue(SleepStageCheck.Stage.DEEP).verdict)
        assertEquals(SleepStageCheck.Verdict.LOW, r.getValue(SleepStageCheck.Stage.REM).verdict)
    }

    @Test fun `deep sleep normally shrinks with age so the same night reads differently`() {
        val young = byStage(light = 280, deep = 50, rem = 100, age = 25).getValue(SleepStageCheck.Stage.DEEP).verdict // 11.6 %
        val older = byStage(light = 280, deep = 50, rem = 100, age = 55).getValue(SleepStageCheck.Stage.DEEP).verdict
        assertEquals(SleepStageCheck.Verdict.LOW, young)
        assertEquals(SleepStageCheck.Verdict.OK, older)
    }

    @Test fun `the range of each stage is reported for display`() {
        val deep = byStage(light = 230, deep = 90, rem = 100).getValue(SleepStageCheck.Stage.DEEP)
        assertEquals(13.0..23.0, deep.typicalPercent)
    }

    @Test fun `a short sleep such as a nap is not judged`() {
        assertNull(SleepStageCheck.assess(lightMin = 60, deepMin = 20, remMin = 10, ageYears = 25))
    }
}
