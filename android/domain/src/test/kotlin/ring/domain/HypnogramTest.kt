package ring.domain

import ring.protocol.commands.SleepStage
import ring.protocol.commands.SleepStageType
import kotlin.test.Test
import kotlin.test.assertEquals

class HypnogramTest {
    private val night = listOf(
        SleepStage(SleepStageType.LIGHT, 45),
        SleepStage(SleepStageType.DEEP, 30),
        SleepStage(SleepStageType.REM, 20),
        SleepStage(SleepStageType.AWAKE, 5),
    )

    @Test fun `stages survive storage in order`() {
        assertEquals("2:45,3:30,4:20,5:5", Hypnogram.encode(night))
        assertEquals(night, Hypnogram.decode(Hypnogram.encode(night)))
    }

    @Test fun `a night stored before the stages existed has none`() {
        assertEquals(emptyList(), Hypnogram.decode(""))
        assertEquals("", Hypnogram.encode(emptyList()))
    }

    @Test fun `damaged pieces are skipped instead of breaking the night`() {
        assertEquals(listOf(SleepStage(SleepStageType.DEEP, 30)), Hypnogram.decode("2,3:30,x:5,9:10,4:-3,"))
    }

    @Test fun `the timeline puts every stage after the previous one`() {
        val blocks = Hypnogram.timeline(start = 1_000L, stages = night)
        assertEquals(listOf(1_000L, 3_700L, 5_500L, 6_700L), blocks.map { it.start })
        assertEquals(7_000L, blocks.last().end)
        assertEquals(SleepStageType.REM, blocks[2].type)
    }
}
