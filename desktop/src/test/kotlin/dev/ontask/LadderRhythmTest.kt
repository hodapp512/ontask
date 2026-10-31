package dev.ontask

import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkSchedule
import dev.ontask.classify.WorkWeek
import dev.ontask.intervene.*
import dev.ontask.plan.*
import dev.ontask.report.DailyReport
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderRhythmTest {
    @Test fun driftEscalatesThroughSteps() {
        val l = Ladder(); var t = 0L
        fun run(until: Long, wasted: Boolean = true, unblocks: Int = 0): Intervention? { var last: Intervention? = null; while (t < until) { l.tick(t, wasted, true, unblocks)?.let { last = it }; t += 10 }; return last }
        assertNull(run(170))
        assertEquals(Step.PROMPT, run(190)?.step)            // 3 min
        assertNull(run(400))                                   // still step 1
        assertEquals(Step.WINDOW, run(490)?.step)             // +5 min => 8 min
        assertEquals(Step.PARTNER, run(1100)?.step)           // +10 min => 18 min
    }

    @Test fun unblocksEscalateAndNotActiveResets() {
        val l = Ladder()
        assertNull(l.tick(0, false, true, 1))
        assertEquals(Step.PROMPT, l.tick(10, false, true, 2)?.step)
        assertEquals(Step.WINDOW, l.tick(20, false, true, 3)?.step)
        assertEquals(Step.PARTNER, l.tick(30, false, true, 4)?.step)
        l.tick(40, false, false, 4); assertEquals(Step.NONE, l.step) // outside work hours/focus
    }

    @Test fun backToWorkGivesGraceAndWorkConfirmedResets() {
        val l = Ladder()
        l.tick(0, true, true, 0); l.tick(200, true, true, 0)
        assertEquals(Step.PROMPT, l.step)
        l.backToWork(200)
        assertNull(l.tick(500, true, true, 0)); assertNull(l.tick(790, true, true, 0)) // within 10 min grace
        assertNull(l.tick(801, true, true, 0))        // drift restarts after grace
        assertEquals(Step.PROMPT, l.tick(1000, true, true, 0)?.step)
        l.workConfirmed(); assertEquals(Step.NONE, l.step)
    }

    @Test fun brieflyNotWastedDoesNotResetDrift() {
        val l = Ladder()
        l.tick(0, true, true, 0); l.tick(30, false, true, 0); l.tick(40, true, true, 0)
        assertEquals(Step.PROMPT, l.tick(185, true, true, 0)?.step)
    }

    @Test fun alertsAreRateLimitedAndExtrasQueued() {
        val a = AlertLimiter(2)
        assertTrue(a.offer("d1", "a")); assertTrue(a.offer("d1", "b")); assertEquals(false, a.offer("d1", "c"))
        assertTrue(a.offer("d2", "x")); assertEquals(listOf("c"), a.drainQueued())
    }

    private val utc = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 26, h, m, 0, 0, utc).toEpochSecond()
    private val sched = WorkSchedule(WorkWeek(mapOf(1 to listOf(TimeRange(9 * 60, 17 * 60)))))
    private val date = LocalDate.of(2026, 10, 26)
    private fun st(first: Long? = at(9, 5), present: Boolean = true, plan: Boolean = false, mid: Boolean = false, eve: Boolean = false) = DayState(date, first, present, plan, mid, eve, null)
    private val r = Rhythm(utc)

    @Test fun rhythmAnchorsToFirstPresence() {
        assertNull(r.due(sched, st(first = null), at(10)))
        assertEquals(Prompt.PLANNING, r.due(sched, st(first = at(9, 5)), at(9, 6)))
        assertEquals(Prompt.PLANNING_SHORT, r.due(sched, st(first = at(13)), at(13, 1)))   // arrived after midday
        assertNull(r.due(sched, st(plan = true), at(10)))                                     // before midpoint (13:02)
        assertEquals(Prompt.MIDDAY, r.due(sched, st(plan = true), at(13, 5)))
        assertNull(r.due(sched, st(plan = true, mid = true), at(15)))
    }

    @Test fun middaySkippedWhenDayStartedLate() {
        assertNull(r.due(sched, st(first = at(15, 30), plan = true), at(16, 30)))
    }

    @Test fun eveningWaitsForReturnThenFallsBackToPhone() {
        assertEquals(Prompt.EVENING, r.due(sched, st(plan = true, mid = true), at(17, 1)))
        assertNull(r.due(sched, st(plan = true, mid = true, present = false), at(17, 30)))
        assertEquals(Prompt.EVENING_PHONE, r.due(sched, st(plan = true, mid = true, present = false), at(18, 5)))
        assertNull(r.due(sched, st(plan = true, mid = true, eve = true), at(17, 30)))
    }

    @Test fun noComputerDayIsNotAMissedCheckin() {
        assertEquals(Prompt.NO_COMPUTER, r.due(sched, st(first = null, present = false), at(17, 1)))
        assertNull(r.due(sched, st(first = null, present = false), at(12)))
        // weekends / days off: nothing
        assertNull(Rhythm(utc).due(WorkSchedule(WorkWeek()), st(), at(10)))
    }

    @Test fun draftMessageIncludesStatsTrendAndGentleClose() {
        fun rep(deep: Long, wasted: Long) = DailyReport(date, 0, mapOf(Level.DEEP to deep, Level.WASTED to wasted), emptyMap(), emptyList(), emptyList(), 0, null, emptyList())
        val m = CheckinDraft.message(rep(14400, 1800), rep(7200, 7200), 4, "Early start", "Reddit", "Phone in other room")
        assertTrue("4h 0m focused" in m && "Better than yesterday" in m && "How the day felt: Good" in m && "Early start" in m && m.endsWith("a 👍 helps."))
        assertEquals("Here is Sam's evening review.", CheckinDraft.intro("Sam Rivera"))
        assertEquals(null, CheckinDraft.intro(null))
    }
}
