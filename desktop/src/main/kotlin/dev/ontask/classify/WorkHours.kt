package dev.ontask.classify

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class TimeRange(val startMin: Int, val endMin: Int)

/** Weekly template. Key = ISO day of week (1 = Monday .. 7 = Sunday). */
@Serializable
data class WorkWeek(val days: Map<Int, List<TimeRange>> = emptyMap()) {
    companion object {
        val DEFAULT = WorkWeek(
            DayOfWeek.entries.filter { it.value <= 5 }.associate { it.value to listOf(TimeRange(9 * 60, 17 * 60)) },
        )
    }
}

/** Half-hour slots of a day, the granularity of the calendar editor. */
object Slots {
    const val PER_DAY = 48
    const val MINUTES = 30

    fun fromRanges(ranges: List<TimeRange>): BooleanArray {
        val s = BooleanArray(PER_DAY)
        for (r in ranges) for (i in 0 until PER_DAY) {
            val a = i * MINUTES; val b = a + MINUTES
            if (a >= r.startMin && b <= r.endMin) s[i] = true
        }
        return s
    }

    fun toRanges(slots: BooleanArray): List<TimeRange> {
        val out = ArrayList<TimeRange>(); var start = -1
        for (i in 0..PER_DAY) {
            val on = i < PER_DAY && slots[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) { out.add(TimeRange(start * MINUTES, i * MINUTES)); start = -1 }
        }
        return out
    }

    /** Minutes that were work time before and are not after (shortened or removed). */
    fun removedMinutes(before: List<TimeRange>, after: List<TimeRange>): Int {
        val b = fromRanges(before); val a = fromRanges(after)
        return (0 until PER_DAY).count { b[it] && !a[it] } * MINUTES
    }
}

/** Weekly template plus per-date exceptions (an exception replaces that whole day; empty list = day off). */
data class WorkSchedule(val week: WorkWeek = WorkWeek.DEFAULT, val exceptions: Map<LocalDate, List<TimeRange>> = emptyMap()) {

    fun rangesFor(date: LocalDate): List<TimeRange> = exceptions[date] ?: week.days[date.dayOfWeek.value].orEmpty()

    fun isWork(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val t = Instant.ofEpochSecond(epochSec).atZone(zone)
        val min = t.hour * 60 + t.minute
        return rangesFor(t.toLocalDate()).any { min >= it.startMin && min < it.endMin }
    }

    fun isWorkDay(date: LocalDate) = rangesFor(date).isNotEmpty()

    /** End of work for [date] in minutes of day, or null on a day off. */
    fun endMin(date: LocalDate): Int? = rangesFor(date).maxOfOrNull { it.endMin }
    fun startMin(date: LocalDate): Int? = rangesFor(date).minOfOrNull { it.startMin }

    /** When the next stretch of work hours begins after [epochSec] (within a week), or null if none is scheduled. */
    fun nextWorkStart(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = Instant.ofEpochSecond(epochSec).atZone(zone)
        for (d in 0..7) {
            val date = t.toLocalDate().plusDays(d.toLong())
            for (r in rangesFor(date).sortedBy { it.startMin }) {
                val start = date.atStartOfDay(zone).plusMinutes(r.startMin.toLong()).toEpochSecond()
                if (start > epochSec) return start
            }
        }
        return null
    }
}
