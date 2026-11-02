package dev.ontask

import dev.ontask.report.DailyReport
import dev.ontask.report.Run
import dev.ontask.report.halves
import dev.ontask.report.morning
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MorningTest {
    private fun day(vararg runs: Run) = DailyReport(LocalDate.of(2026, 11, 2), 0, emptyMap(), emptyMap(), runs.toList(), emptyList(), 0, null, emptyList())
    private fun run(a: Int, b: Int, l: Level, name: String = "x") = Run(a * 60L, b * 60L, l, mapOf(name to (b - a) * 60L))

    @Test fun aFocusedMorningIsNotAStruggle() {
        val m = morning(day(run(0, 50, Level.DEEP, "IntelliJ"), run(50, 60, Level.WASTED, "YouTube"), run(60, 120, Level.DEEP, "IntelliJ")), 120 * 60)
        assertEquals(110 * 60L, m.focusedSec); assertEquals(91, m.pct); assertEquals(listOf("IntelliJ", "YouTube"), m.top)
        assertEquals(60 * 60L, m.longestFocusSec)
        assertTrue(!m.struggling)
    }

    @Test fun aScatteredMorningThatDidntImproveIsAStruggle() {
        val m = morning(day(run(0, 30, Level.DEEP), run(30, 90, Level.WASTED), run(90, 110, Level.DEEP), run(110, 150, Level.WASTED)), 150 * 60)
        assertTrue(m.struggling)
        // …but one that got better in its last hour isn't.
        val better = morning(day(run(0, 20, Level.DEEP), run(20, 90, Level.WASTED), run(90, 150, Level.DEEP)), 150 * 60)
        assertTrue(!better.struggling)
    }

    @Test fun breaksDontCountInTheLastHour() {
        // Focused before a 30-minute break of YouTube: the last hour is the focused time before it.
        val runs = arrayOf(run(0, 40, Level.WASTED), run(40, 120, Level.DEEP), run(120, 150, Level.WASTED))
        val withBreak = morning(day(*runs), 150 * 60, listOf(120 * 60L to 150 * 60L))
        assertEquals(100, withBreak.lastHourPct)
        assertTrue(!withBreak.struggling)
    }

    @Test fun halvesSplitAtLunch() {
        val (am, pm) = halves(day(run(0, 60, Level.DEEP), run(60, 120, Level.WASTED), run(120, 180, Level.WASTED), run(180, 240, Level.DEEP)), 120 * 60, 180 * 60)
        assertEquals(50, am); assertEquals(100, pm)
    }
}
