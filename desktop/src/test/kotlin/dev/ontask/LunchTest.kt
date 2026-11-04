package dev.ontask

import dev.ontask.intervene.Breaks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LunchTest {
    @Test fun lunchRunsOutThenStillAtLunchAddsFifteen() {
        val b = Breaks(); val start = 1_000_000L
        b.startLunch(start, "2026-11-04")
        assertFalse(b.lunchOffered("2026-11-04", 12 * 60))
        var due: Breaks.Due? = null
        var t = start
        while (due != Breaks.Due.LUNCH_OVER) { t += 5; due = b.tick(t, 5, null) ?: due.takeIf { it == Breaks.Due.LUNCH_OVER } }
        assertEquals(start + 45 * 60, t)
        assertTrue(b.lunchJustEnded(t + 60))
        b.moreLunch(t + 60)
        assertTrue(b.onBreak())
        assertEquals(t + 60 + 15 * 60, b.active!!.until)
        assertFalse(b.lunchJustEnded(t + 120)) // on lunch again, nothing to offer
    }
}
