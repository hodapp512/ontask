package dev.ontask

import dev.ontask.report.Patterns
import dev.ontask.report.Patterns.Metric
import dev.ontask.report.Patterns.Seg
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PatternsTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun averagesEveryDayIncludingEmptyOnes() {
        // 9:00–9:30 focused on Nov 2 only; Nov 3 empty; tracking began Nov 2.
        val days = mapOf(LocalDate.of(2026, 11, 2) to listOf(Seg(9 * 3600, 9 * 3600 + 1800, 'D')))
        val bars = Patterns.distribution(days, Patterns.Range.YEAR.from(today), today, LocalDate.of(2026, 11, 2), 60, Metric.FOCUSED, nowMin = 12 * 60)
        val nine = bars[9]
        assertEquals(3, nine.days)            // Nov 2, 3 and 4 (9 AM has passed today); Year is cut off at Nov 2
        assertEquals(600.0, nine.avgSec)      // 30 min over 3 days
        assertEquals(1, nine.daysWithAny)
        assertEquals(2, bars[15].days)        // 3 PM hasn't come yet today
    }

    @Test fun stretchesSplitAcrossSlotsAndMetrics() {
        val days = mapOf(today to listOf(Seg(8 * 3600 + 1800, 10 * 3600, 'W'), Seg(10 * 3600, 11 * 3600, 'M'), Seg(11 * 3600, 12 * 3600, 'A')))
        val w = Patterns.distribution(days, today, today, today, 60, Metric.WASTED, 24 * 60)
        assertEquals(listOf(1800.0, 3600.0), listOf(w[8].avgSec, w[9].avgSec))
        val s = Patterns.distribution(days, today, today, today, 60, Metric.SCREEN, 24 * 60)
        assertEquals(listOf(3600.0, 0.0), listOf(s[10].avgSec, s[11].avgSec)) // Neutral counts as screen time, Away doesn't
        assertEquals(Patterns.Total(9000, 9000, 1), Patterns.total(days, today, today, today, Metric.SCREEN))
        assertEquals("9–10 AM · 60 min wasted on average · on 1 of 1 days", Patterns.describe(w[9], 60, Metric.WASTED))
    }

    @Test fun ranges() {
        assertEquals(LocalDate.of(2026, 11, 2), Patterns.Range.THIS_WEEK.from(today)) // Monday
        assertEquals(LocalDate.of(2026, 11, 1), Patterns.Range.THIS_MONTH.from(today))
        assertEquals(LocalDate.of(2026, 10, 6), Patterns.Range.LAST_30.from(today))
    }
}
