package dev.ontask.report

import dev.ontask.Level

/**
 * The morning in numbers for the midday check-in: from the start of the day until [until] (lunch, or now).
 * [struggling]: under 60% of screen time focused or 30+ min wasted, and the last hour before [until] (breaks and away
 * time left out, so lunch or a break never makes it look worse) no better than the morning overall.
 */
data class Morning(
    val focusedSec: Long, val wastedSec: Long, val activeSec: Long, val longestFocusSec: Long,
    val top: List<String>, val lastHourPct: Int?, val struggling: Boolean,
) {
    val pct: Int get() = if (activeSec > 0) (focusedSec * 100 / activeSec).toInt() else 0
}

private fun clip(r: Run, a: Long, b: Long): Long = (minOf(r.end, b) - maxOf(r.start, a)).coerceAtLeast(0)

fun morning(r: DailyReport, until: Long, exclude: List<Pair<Long, Long>> = emptyList()): Morning {
    val runs = r.timeline.filter { it.start < until && it.level != Level.AWAY }
    fun sum(l: Level) = runs.filter { it.level == l }.sumOf { clip(it, r.dayStart, until) }
    val focused = sum(Level.DEEP); val wasted = sum(Level.WASTED); val active = focused + wasted + sum(Level.MIXED)

    // Longest focused stretch: focused runs with at most a minute between them.
    var longest = 0L; var a = -1L; var b = -1L
    for (run in runs.filter { it.level == Level.DEEP }) {
        val e = minOf(run.end, until)
        if (a < 0 || run.start - b > 60) { a = run.start; b = e } else b = maxOf(b, e)
        longest = maxOf(longest, b - a)
    }

    val byName = HashMap<String, Long>()
    runs.forEach { run -> run.activity.forEach { (n, s) -> byName.merge(n, s, Long::plus) } }
    val top = byName.entries.sortedByDescending { it.value }.take(2).map { it.key }

    // The last hour of screen time before [until], walking back, skipping excluded stretches (breaks).
    var need = 3600L; var lf = 0L; var la = 0L
    for (run in runs.sortedByDescending { it.start }) {
        if (need <= 0) break
        var s = clip(run, r.dayStart, until)
        exclude.forEach { (x, y) -> s -= (minOf(run.end, until, y) - maxOf(run.start, x)).coerceAtLeast(0) }
        s = minOf(s.coerceAtLeast(0), need)
        la += s; if (run.level == Level.DEEP) lf += s; need -= s
    }
    val lastPct = if (la >= 1800) (lf * 100 / la).toInt() else null
    val pct = if (active > 0) (focused * 100 / active).toInt() else 0
    val struggling = active >= 1800 && (pct < 60 || wasted >= 1800) && (lastPct == null || lastPct <= pct)
    return Morning(focused, wasted, active, longest, top, lastPct, struggling)
}

/** Focused share before [split] (lunch, or noon) and after [resume] (lunch's end), for "Morning 64% focused · Afternoon 48%". */
fun halves(r: DailyReport, split: Long, resume: Long = split): Pair<Int?, Int?> {
    fun pct(a: Long, b: Long): Int? {
        val runs = r.timeline.filter { it.level != Level.AWAY }
        val act = runs.sumOf { clip(it, a, b) }
        return if (act < 600) null else (runs.filter { it.level == Level.DEEP }.sumOf { clip(it, a, b) } * 100 / act).toInt()
    }
    return pct(r.dayStart, split) to pct(resume, Long.MAX_VALUE)
}
