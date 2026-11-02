package dev.ontask.report

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * One day for the year heat map: screen time split by work hours. [deep]/[active] are the whole day; work-hours and
 * off-hours parts feed the two factors of [effectiveSec].
 */
data class DayStat(
    val deep: Long, val active: Long,
    val workDeep: Long, val workActive: Long,
    val offWasted: Long, val offActive: Long,
) {
    /** Share of the day's screen time that was focused (the tooltip's %). */
    val pct: Int get() = if (active > 0) (deep * 100 / active).toInt() else 0

    /**
     * Effective focus: focused time (whole day, so evening work counts) × (0.5 + 0.5 × work-hours focused %) ×
     * (0.8 + 0.2 × off-hours not-wasted %). A side with no time counts as 1, so not using the computer never costs.
     */
    val effectiveSec: Long get() {
        val on = if (workActive > 0) 0.5 + 0.5 * workDeep / workActive else 1.0
        val off = if (offActive > 0) 0.8 + 0.2 * (offActive - offWasted) / offActive else 1.0
        return (deep * on * off).toLong()
    }
}

object Heat {
    /** Shade 0 (no computer) to 5: under 1h, then 1.5-hour steps (1–2.5h, 2.5–4h, 4–5.5h, 5.5h+). */
    fun level(d: DayStat?): Int {
        if (d == null || d.active <= 0) return 0
        val h = d.effectiveSec / 3600.0
        return when { h < 1 -> 1; h < 2.5 -> 2; h < 4 -> 3; h < 5.5 -> 4; else -> 5 }
    }

    const val STREAK_SEC = (2.5 * 3600).toLong()

    /**
     * Consecutive days, back from [today], with 2.5h+ effective focus. Days off ([workDay] false) are skipped either
     * way; today doesn't break the streak while it's still under way.
     */
    fun streak(days: Map<LocalDate, DayStat>, today: LocalDate, workDay: (LocalDate) -> Boolean, limit: Int = 400): Int {
        var n = 0; var d = today
        repeat(limit) {
            val ok = (days[d]?.effectiveSec ?: 0) >= STREAK_SEC
            if (ok) n++ else if (workDay(d) && d != today) return n
            d = d.minusDays(1)
        }
        return n
    }

    /** The first day shown: the Sunday 51 weeks before this week's Sunday (52 columns ending with this week). */
    fun firstDay(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(51)
}
