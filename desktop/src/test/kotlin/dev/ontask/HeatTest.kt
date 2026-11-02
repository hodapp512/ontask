package dev.ontask

import dev.ontask.report.DayStat
import dev.ontask.report.Heat
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HeatTest {
    private val h = 3600L

    @Test fun effectiveFocusFollowsTheFormula() {
        // 4h focused, 80% in work hours, no evening use: 4h × 0.9 × 1.
        assertEquals((3.6 * h).toLong(), DayStat(4 * h, 5 * h, 4 * h, 5 * h, 0, 0).effectiveSec)
        // The same day plus an evening hour of bills: not wasted, costs nothing (and adds no focus).
        assertEquals((3.6 * h).toLong(), DayStat(4 * h, 6 * h, 4 * h, 5 * h, 0, h).effectiveSec)
        // …or an evening hour of YouTube: ×0.8.
        assertEquals((2.88 * h).toLong(), DayStat(4 * h, 6 * h, 4 * h, 5 * h, h, h).effectiveSec)
        // A Saturday with 2h of side project and 1h of YouTube: no work hours (×1), 67% not wasted.
        assertEquals(6720.0, DayStat(2 * h, 3 * h, 0, 0, h, 3 * h).effectiveSec.toDouble(), 1.0) // 1h 52m
    }

    @Test fun shadesStepEveryHourAndAHalf() {
        fun lvl(hours: Double) = Heat.level(DayStat((hours * h).toLong(), (hours * h).toLong(), (hours * h).toLong(), (hours * h).toLong(), 0, 0))
        assertEquals(0, Heat.level(null)); assertEquals(1, lvl(0.5)); assertEquals(2, lvl(1.0)); assertEquals(2, lvl(2.4))
        assertEquals(3, lvl(2.5)); assertEquals(4, lvl(4.0)); assertEquals(5, lvl(5.5)); assertEquals(5, lvl(10.0))
    }

    @Test fun streakSkipsDaysOffAndTodayInProgress() {
        val mon = LocalDate.of(2026, 11, 2) // Monday
        val good = DayStat(3 * h, 3 * h, 3 * h, 3 * h, 0, 0)
        val days = mapOf(mon.minusDays(3) to good, mon.minusDays(4) to good, mon.minusDays(5) to DayStat(h, h, h, h, 0, 0))
        val workDay = { d: LocalDate -> d.dayOfWeek.value <= 5 }
        // Today (Mon) not there yet; Sat/Sun off; Fri and Thu good; Wed weak ends it.
        assertEquals(2, Heat.streak(days, mon, workDay))
        assertEquals(LocalDate.of(2025, 11, 9), Heat.firstDay(mon))
    }
}
