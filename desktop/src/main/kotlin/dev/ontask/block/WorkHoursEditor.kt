package dev.ontask.block

import dev.ontask.classify.Slots
import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkSchedule
import dev.ontask.classify.WorkWeek
import dev.ontask.db.Settings
import dev.ontask.db.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class ReasonRequired(val removedMinutes: Int) : Exception("Shortening work hours needs a reason")

/** Applies work-hours edits. Removing/shortening work time needs a reason and notifies the partner; adding never does. */
class WorkHoursEditor(private val store: SettingsStore, private val events: Events, private val current: () -> Settings, private val apply: (Settings) -> Unit) {

    /** Total minutes of work time removed by replacing [before] with [after] on the given days. */
    fun removedMinutes(before: WorkSchedule, after: WorkSchedule, dates: List<LocalDate>): Int =
        dates.sumOf { Slots.removedMinutes(before.rangesFor(it), after.rangesFor(it)) }

    fun saveTemplate(week: WorkWeek, reason: String?, now: Long) {
        val cur = current()
        // Compare templates only; exceptions are edited separately.
        val removed = DayOfWeek.entries.sumOf { Slots.removedMinutes(cur.schedule.week.days[it.value].orEmpty(), week.days[it.value].orEmpty()) }
        if (removed > 0 && reason.isNullOrBlank()) throw ReasonRequired(removed)
        val next = cur.copy(schedule = cur.schedule.copy(week = week))
        store.save(next); apply(next)
        if (removed > 0) events.emit(
            "work_hours_change", now, "scope" to "weekly template", "removed_minutes" to removed, "reason" to reason!!.trim(),
            "meaning" to "Weekly work hours were reduced by ${fmt(removed)}. Blocking and monitoring will be off during that time.",
        )
    }

    /** [ranges] = null removes the exception (day goes back to the template). */
    fun saveException(date: LocalDate, ranges: List<TimeRange>?, reason: String?, now: Long) {
        val cur = current()
        val after = cur.schedule.copy(exceptions = if (ranges == null) cur.schedule.exceptions - date else cur.schedule.exceptions + (date to ranges))
        val removed = Slots.removedMinutes(cur.schedule.rangesFor(date), after.rangesFor(date))
        if (removed > 0 && reason.isNullOrBlank()) throw ReasonRequired(removed)
        store.saveException(date, ranges)
        apply(cur.copy(schedule = after))
        if (removed > 0) events.emit(
            "work_hours_change", now, "scope" to "exception", "date" to date.toString(), "removed_minutes" to removed, "reason" to reason!!.trim(),
            "meaning" to "Work hours on ${date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US))} were reduced by ${fmt(removed)}. Blocking and monitoring will be off during that time.",
        )
    }

    private fun fmt(min: Int) = if (min % 60 == 0) "${min / 60}h" else if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}
