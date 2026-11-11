package ring.domain

import ring.protocol.commands.SleepStage
import ring.protocol.commands.SleepStageType

/** A stretch of one sleep stage on the time axis (epoch seconds). */
data class StageBlock(val type: SleepStageType, val start: Long, val end: Long)

/** The ordered stages of a night, stored as "code:minutes" pairs ("2:45,3:30") and laid out on the time axis for the hypnogram. */
object Hypnogram {
    private const val SECONDS_PER_MINUTE = 60L
    private val knownCodes = SleepStageType.entries.filter { it != SleepStageType.UNKNOWN }.associateBy { it.code }

    fun encode(stages: List<SleepStage>): String = stages.joinToString(",") { "${it.type.code}:${it.minutes}" }

    /** Pieces that are damaged, of an unknown stage or without minutes are skipped, so one bad piece never hides the night. */
    fun decode(text: String): List<SleepStage> = text.split(",").mapNotNull { piece ->
        val (code, minutes) = piece.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        val type = code.toIntOrNull()?.let(knownCodes::get)
        val length = minutes.toIntOrNull()?.takeIf { it > 0 }
        if (type == null || length == null) null else SleepStage(type, length)
    }

    fun timeline(start: Long, stages: List<SleepStage>): List<StageBlock> {
        var at = start
        return stages.map { stage ->
            StageBlock(stage.type, at, at + stage.minutes * SECONDS_PER_MINUTE).also { at = it.end }
        }
    }
}
