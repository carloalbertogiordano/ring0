package ring.domain

/** Helpers to draw and analyse a series that has holes where the ring was not worn. */
object Series {
    private const val MIN_GAP_SECONDS = 1800L
    private const val SPACING_FACTOR = 3.0

    /**
     * Splits the points where the time between two consecutive ones is more than three times the usual spacing of the
     * series (and at least 30 minutes), so a chart can leave the empty stretch blank instead of joining the dots.
     */
    fun segments(points: List<TimedValue>): List<List<TimedValue>> {
        val sorted = points.sortedBy { it.ts }
        if (sorted.size < 2) return if (sorted.isEmpty()) emptyList() else listOf(sorted)
        val spacing = Stats.median(sorted.zipWithNext { a, b -> (b.ts - a.ts).toDouble() })
        val limit = maxOf(MIN_GAP_SECONDS.toDouble(), SPACING_FACTOR * spacing)
        val segments = mutableListOf(mutableListOf(sorted.first()))
        for ((previous, point) in sorted.zipWithNext()) {
            if (point.ts - previous.ts > limit) segments += mutableListOf(point) else segments.last() += point
        }
        return segments
    }

    /** The empty stretches between consecutive segments. */
    fun holes(segments: List<List<TimedValue>>): List<WearGap> =
        segments.zipWithNext { a, b -> WearGap(a.last().ts, b.first().ts) }
}
