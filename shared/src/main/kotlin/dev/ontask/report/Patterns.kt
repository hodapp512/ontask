package dev.ontask.report

import java.time.LocalDate

/**
 * "Your patterns": when in the day you tend to be focused, wasting time or on a screen at all, averaged over a range
 * of days, from the merged day reports (Mac and phone, overlaps counted once). Shared by the Mac and the phone.
 */
object Patterns {
    /** One stretch of a day: seconds after the day's start, and its level's letter (D, M, W, A). */
    data class Seg(val a: Int, val b: Int, val k: Char)

    enum class Metric(val label: String, val verb: String) {
        FOCUSED("Focused", "focused"), WASTED("Wasted", "wasted"), SCREEN("Screen time", "on a screen");
        fun counts(k: Char) = when (this) { FOCUSED -> k == 'D'; WASTED -> k == 'W'; SCREEN -> k != 'A' }
    }

    enum class Range(val label: String) {
        TODAY("Today"), LAST_7("Last 7 days"), LAST_30("Last 30 days"), YEAR("Last 365 days");
        fun from(today: LocalDate): LocalDate = when (this) {
            TODAY -> today
            LAST_7 -> today.minusDays(6)
            LAST_30 -> today.minusDays(29)
            YEAR -> today.minusDays(364)
        }
        companion object {
            /** A saved choice, including ones that are gone (calendar week and month, 6 months). */
            fun saved(name: String?): Range = when (name) {
                "THIS_WEEK" -> LAST_7; "THIS_MONTH", "SIX_MONTHS" -> LAST_30
                else -> entries.firstOrNull { it.name == name } ?: LAST_30
            }
        }
    }

    /** Slot sizes in minutes; 15 is offered on the Mac only. */
    val SLOTS = listOf(15, 30, 60, 120)
    const val DEFAULT_SLOT = 60

    /** One bar: the slot's start (minutes after midnight), average seconds per day, and on how many days it had any. */
    data class Bar(val startMin: Int, val avgSec: Double, val daysWithAny: Int, val days: Int)

    /** Seconds of [metric] in each slot of one day. */
    private fun slots(segs: List<Seg>, slotMin: Int, metric: Metric): DoubleArray {
        val n = 1440 / slotMin; val out = DoubleArray(n); val w = slotMin * 60
        for (s in segs) {
            if (!metric.counts(s.k)) continue
            var t = s.a.coerceAtLeast(0); val end = s.b.coerceAtMost(86_400)
            while (t < end) {
                val i = t / w; if (i >= n) break
                val stop = minOf(end, (i + 1) * w)
                out[i] += (stop - t).toDouble(); t = stop
            }
        }
        return out
    }

    /**
     * The bars for days [from]..[today], starting no earlier than [first] (when tracking began). Every day counts,
     * empty ones as zero; today only for slots that have started by [nowMin] (minutes after midnight).
     */
    fun distribution(days: Map<LocalDate, List<Seg>>, from: LocalDate, today: LocalDate, first: LocalDate?, slotMin: Int, metric: Metric, nowMin: Int): List<Bar> {
        val n = 1440 / slotMin
        val start = maxOf(from, first ?: today)
        val sum = DoubleArray(n); val any = IntArray(n); val count = IntArray(n)
        var d = start
        while (!d.isAfter(today)) {
            val s = slots(days[d].orEmpty(), slotMin, metric)
            for (i in 0 until n) {
                if (d == today && i * slotMin >= nowMin) continue
                count[i]++; sum[i] += s[i]; if (s[i] >= 60) any[i]++
            }
            d = d.plusDays(1)
        }
        return (0 until n).map { i -> Bar(i * slotMin, if (count[i] == 0) 0.0 else sum[i] / count[i], any[i], count[i]) }
    }

    /** Total seconds of [metric] over [from]..[today] (no earlier than [first]) and the average per day. */
    data class Total(val sec: Long, val avgSec: Long, val days: Int)

    fun total(days: Map<LocalDate, List<Seg>>, from: LocalDate, today: LocalDate, first: LocalDate?, metric: Metric): Total {
        val start = maxOf(from, first ?: today)
        var sec = 0L; var n = 0; var d = start
        while (!d.isAfter(today)) {
            sec += days[d].orEmpty().filter { metric.counts(it.k) }.sumOf { (it.b.coerceAtMost(86_400) - it.a.coerceAtLeast(0)).coerceAtLeast(0).toLong() }
            n++; d = d.plusDays(1)
        }
        return Total(sec, if (n == 0) 0 else sec / n, n)
    }

    /** "9 AM", "9:30 AM", "12 PM". */
    fun clock(min: Int): String {
        val h = (min / 60) % 24; val m = min % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return (if (m == 0) "$h12" else "$h12:${m.toString().padStart(2, '0')}") + if (h < 12) " AM" else " PM"
    }

    /** The axis label under a bar: "12a", "3p". */
    fun axis(min: Int): String { val h = (min / 60) % 24; return (if (h % 12 == 0) 12 else h % 12).toString() + if (h < 12) "a" else "p" }

    /** "9–10 AM · 18 min focused on average" ([oneDay]: the Today range, "9–10 AM · 18 min focused"). */
    fun describe(b: Bar, slotMin: Int, metric: Metric, oneDay: Boolean = false): String {
        val avgMin = Math.round(b.avgSec / 60)
        val x = clock(b.startMin); val y = clock(b.startMin + slotMin)
        // "9–10 AM" when both ends share AM or PM.
        val from = if (x.takeLast(2) == y.takeLast(2)) x.dropLast(3) else x
        return "$from–$y · $avgMin min ${metric.verb}" + if (oneDay) "" else " on average"
    }

    /** "1h 56m", "18m". */
    fun fmt(sec: Long): String { val m = sec / 60; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
}
