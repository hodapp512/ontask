package dev.ontask.plan

import dev.ontask.classify.WorkSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Prompt { PLANNING, PLANNING_SHORT, MIDDAY, EVENING, EVENING_PHONE, NO_COMPUTER }

data class DayState(
    val date: LocalDate,
    val firstPresenceTs: Long?,        // first non-away tick today
    val presentNow: Boolean,
    val planningDone: Boolean,
    val middayDone: Boolean,
    val eveningDone: Boolean,
    val lastPresentTs: Long?,
)

/**
 * Decides which daily-rhythm prompt is due. The day is anchored to first presence at the computer on a work day,
 * not to clock time. Pure function of (schedule, state, now) so it is easy to test.
 */
/** [eveningLeadMin]: the evening check-in comes this many minutes before work hours end (default 30). */
class Rhythm(private val zone: ZoneId = ZoneId.systemDefault(), private val middayEnabled: Boolean = true, private val eveningEnabled: Boolean = true, private val eveningLeadMin: Int = 30) {

    fun due(schedule: WorkSchedule, s: DayState, now: Long): Prompt? {
        val date = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        if (date != s.date || !schedule.isWorkDay(date)) return null
        val dayStart = date.atStartOfDay(zone).toEpochSecond()
        val endTs = dayStart + schedule.endMin(date)!! * 60L
        // While you're still at your desk, so the reflection is part of the day's report.
        val eveningTs = endTs - eveningLeadMin * 60L
        val first = s.firstPresenceTs

        if (first == null) {
            // A work day with no computer use is "no computer today", not a missed check-in.
            return if (now >= endTs && !s.eveningDone) Prompt.NO_COMPUTER else null
        }
        if (now >= eveningTs) {
            if (s.eveningDone || !eveningEnabled) return null
            if (s.presentNow) return Prompt.EVENING
            // Already left: wait for return, or fall back to the phone in the evening.
            return if (now >= endTs + 3600) Prompt.EVENING_PHONE else null
        }
        if (!s.planningDone && s.presentNow) {
            val midday = dayStart + 12 * 3600L
            return if (now >= midday) Prompt.PLANNING_SHORT else Prompt.PLANNING
        }
        if (middayEnabled && !s.middayDone && s.presentNow && s.planningDone) {
            // Skipped when the day started less than ~2h before the end of work hours.
            if (endTs - first < 2 * 3600L) return null
            val midpoint = first + (endTs - first) / 2
            if (now >= midpoint) return Prompt.MIDDAY
        }
        return null
    }
}
