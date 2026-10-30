package dev.ontask.classify

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

@Serializable
data class TimeRange(val startMin: Int, val endMin: Int)

/** Minimal weekly template. Key = ISO day of week (1 = Monday .. 7 = Sunday). Phase 2 adds per-day exceptions. */
@Serializable
data class WorkWeek(val days: Map<Int, List<TimeRange>> = emptyMap()) {

    fun isWork(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochSecond(epochSec).atZone(zone)
        val min = t.hour * 60 + t.minute
        return days[t.dayOfWeek.value].orEmpty().any { min >= it.startMin && min < it.endMin }
    }

    companion object {
        val DEFAULT = WorkWeek(
            DayOfWeek.entries.filter { it.value <= 5 }.associate { it.value to listOf(TimeRange(9 * 60, 17 * 60)) },
        )
    }
}
