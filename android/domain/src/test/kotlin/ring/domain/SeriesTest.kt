package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals

private fun at(vararg seconds: Long) = seconds.map { TimedValue(it, 1.0) }

class SeriesTest {
    @Test fun `a long hole splits the series into segments`() {
        val points = (0L until 6).map { it * 300 } + (30L until 36).map { it * 300 }
        val segments = Series.segments(at(*points.toLongArray()))
        assertEquals(listOf(6, 6), segments.map { it.size })
    }

    @Test fun `regular spacing without holes is one segment`() {
        val points = (0L until 20).map { it * 1800 }
        assertEquals(1, Series.segments(at(*points.toLongArray())).size)
    }

    @Test fun `the gap threshold follows the spacing of the series`() {
        // hourly samples: a missing sample (2 h) is not a gap, 4 h is
        val missingOne = at(0, 3600, 7200, 14_400, 18_000, 21_600)
        assertEquals(1, Series.segments(missingOne).size)
        val missingThree = at(0, 3600, 7200, 21_600, 25_200, 28_800)
        assertEquals(2, Series.segments(missingThree).size)
    }

    @Test fun `unsorted input is sorted and tiny inputs are safe`() {
        assertEquals(emptyList(), Series.segments(emptyList()))
        assertEquals(listOf(1), Series.segments(at(100)).map { it.size })
        assertEquals(listOf(0L, 300L, 600L), Series.segments(at(600, 0, 300)).single().map { it.ts })
    }

    @Test fun `gaps between segments are reported as time ranges`() {
        val points = at(0, 300, 600, 10_000, 10_300, 10_600)
        assertEquals(listOf(WearGap(600, 10_000)), Series.holes(Series.segments(points)))
    }
}

class SeriesEdgesTest {
    @Test fun `no data before the first or after the last point is a hole too`() {
        val segments = Series.segments(at(10_000, 10_300, 10_600))
        assertEquals(listOf(WearGap(0, 10_000), WearGap(10_600, 20_000)), Series.holes(segments, from = 0, to = 20_000))
    }

    @Test fun `a short margin at the edges is not a hole`() {
        val segments = Series.segments(at(600, 900, 1200))
        assertEquals(emptyList(), Series.holes(segments, from = 0, to = 1500))
    }

    @Test fun `without any data the whole window is a hole`() {
        assertEquals(listOf(WearGap(0, 5000)), Series.holes(emptyList(), from = 0, to = 5000))
    }
}

class WearContainsTest {
    @Test fun `a timestamp inside a gap is not worn`() {
        val gaps = listOf(WearGap(1000, 2000), WearGap(5000, 6000))
        assertEquals(true, Wear.inGap(1500, gaps))
        assertEquals(true, Wear.inGap(1000, gaps))
        assertEquals(false, Wear.inGap(2000, gaps))
        assertEquals(false, Wear.inGap(3000, gaps))
    }
}

class SeriesNearestTest {
    private val dense = (0L until 12).map { TimedValue(it * 300, it.toDouble()) } + (60L until 66).map { TimedValue(it * 300, it.toDouble()) }

    @Test fun `a touch snaps to the nearest sample`() {
        assertEquals(3.0, Series.nearest(dense, 3 * 300 + 100)?.value)
        assertEquals(4.0, Series.nearest(dense, 3 * 300 + 200)?.value)
        assertEquals(0.0, Series.nearest(dense, 0)?.value)
    }

    @Test fun `a touch in the middle of a hole finds nothing`() {
        assertEquals(null, Series.nearest(dense, 30 * 300))
    }

    @Test fun `near the edge of a hole it still snaps to the last sample`() {
        assertEquals(11.0, Series.nearest(dense, 11 * 300 + 400)?.value)
    }

    @Test fun `before the first and after the last sample`() {
        assertEquals(0.0, Series.nearest(dense, -500)?.value)
        assertEquals(null, Series.nearest(dense, -20_000))
        assertEquals(null, Series.nearest(dense, 90_000 * 10))
    }

    @Test fun `empty and single point series are safe`() {
        assertEquals(null, Series.nearest(emptyList(), 100))
        assertEquals(7.0, Series.nearest(listOf(TimedValue(1000, 7.0)), 1100)?.value)
        assertEquals(null, Series.nearest(listOf(TimedValue(1000, 7.0)), 1000 + 5000))
    }
}
