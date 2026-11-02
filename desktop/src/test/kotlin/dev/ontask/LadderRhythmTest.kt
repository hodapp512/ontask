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
    private fun Breaks.run(from: Long, to: Long, level: Level?): Breaks.Due? { var due: Breaks.Due? = null; var t = from; while (t < to) { tick(t, 5, level)?.let { due = it }; t += 5 }; return due }

    @Test fun breaksAreEarnedByFocusAndLostToOtherTime() {
        val b = Breaks()
        b.run(0, 24 * 60, Level.DEEP); assertEquals(0, b.earnedSec())               // under 25 min: nothing
        b.run(24 * 60, 30 * 60, Level.DEEP); assertEquals(300, b.earnedSec())      // 30 min → 5
        b.run(1800, 1830, Level.WASTED); assertEquals(1800, b.focusedSec)          // a dip under a minute is ignored
        b.run(1830, 1840, Level.DEEP)
        b.run(1840, 1840 + 6 * 60, Level.MIXED); assertEquals(1810 - 360, b.focusedSec) // 6 min of email: minute for minute
        b.run(3000, 3000 + 60 * 60, Level.DEEP); assertEquals(900, b.earnedSec())  // over an hour: up to 15
        b.run(7000 + 3600, 7300 + 3600, null); assertEquals(0, b.focusedSec)                     // 5 min away starts again
    }

    @Test fun earnedCapsAtTenUnderAnHour() {
        val b = Breaks(); b.run(0, 55 * 60, Level.DEEP); assertEquals(600, b.earnedSec())
    }

    @Test fun aBreakRunsOutOrEndsQuietlyWhenBackAtWork() {
        val b = Breaks(); b.run(0, 30 * 60, Level.DEEP)
        b.startBreak(1800); assertTrue(b.onBreak())
        assertNull(b.run(1800, 2095, Level.WASTED))
        assertEquals(Breaks.Due.BREAK_OVER, b.run(2095, 2110, Level.WASTED)); assertEquals(0, b.focusedSec)
        assertTrue(b.canExtend(2110)); assertTrue(b.extend(2110)); assertTrue(b.onBreak())
        assertEquals(Breaks.Due.BREAK_OVER, b.run(2110, 2420, Level.WASTED)); assertTrue(!b.canExtend(2420)) // once
        assertEquals(610, b.breakSec(2420, 0)) // the 10 s the card was up counts too
        val c = Breaks(); c.run(0, 30 * 60, Level.DEEP); c.startBreak(1800)
        assertNull(c.run(1800, 1800 + 125, Level.DEEP)); assertTrue(!c.onBreak())   // 2 min of work ends it quietly
    }

    @Test fun lunchAsksAtThirtyAndEndsAtFortyFiveEvenWhileWorking() {
        val b = Breaks()
        assertTrue(b.lunchOffered("d", 12 * 60)); assertTrue(!b.lunchOffered("d", 10 * 60 + 59))
        b.startLunch(0, "d"); assertTrue(!b.lunchOffered("d", 12 * 60))
        assertEquals(Breaks.Due.LUNCH_CHECK, b.run(0, 30 * 60 + 5, Level.DEEP)); assertTrue(b.onBreak())
        assertEquals(Breaks.Due.LUNCH_OVER, b.run(30 * 60 + 5, 45 * 60 + 5, Level.DEEP)); assertTrue(!b.onBreak())
    }

    @Test fun driftingOnAfterABreakGoesStraightToStepTwo() {
        val l = Ladder()
        l.breakOver(0)
        assertNull(l.tick(0, true, true, 0)); assertEquals(Step.WINDOW, l.tick(65, true, true, 0)?.step)
        val m = Ladder(); m.breakOver(0)
        m.tick(0, false, true, 0); m.tick(70, false, true, 0)                       // back at work: forgotten
        m.tick(100, true, true, 0); assertEquals(Step.PROMPT, m.tick(225, true, true, 0)?.step)
    }

    @Test fun driftEscalatesThroughSteps() {
        val l = Ladder(); var t = 0L
        fun run(until: Long, wasted: Boolean = true, unblocks: Int = 0): Intervention? { var last: Intervention? = null; while (t < until) { l.tick(t, wasted, true, unblocks)?.let { last = it }; t += 10 }; return last }
        assertNull(run(110))
        assertEquals(Step.PROMPT, run(130)?.step)            // 2 min
        assertNull(run(290))                                   // still step 1
        assertEquals(Step.WINDOW, run(310)?.step)             // +3 min => 5 min
        assertEquals(Step.PARTNER, run(610)?.step)            // +5 min => 10 min
    }

    @Test fun driftingAgainWithinTheHourPicksUpWhereItLeftOff() {
        val l = Ladder()
        l.tick(0, true, true, 0); assertEquals(Step.PROMPT, l.tick(125, true, true, 0)?.step)
        l.backToWork(130)
        // After the 5-minute grace, a new drift's first prompt is already step 2…
        l.tick(440, true, true, 0); assertEquals(Step.WINDOW, l.tick(565, true, true, 0)?.step)
        l.backToWork(570)
        // …and the next one step 3.
        l.tick(880, true, true, 0); assertEquals(Step.PARTNER, l.tick(1005, true, true, 0)?.step)
        l.backToWork(1010)
        // More than an hour later it starts gently again.
        l.tick(5000, true, true, 0); assertEquals(Step.PROMPT, l.tick(5125, true, true, 0)?.step)
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
        l.tick(0, true, true, 0); l.tick(130, true, true, 0)
        assertEquals(Step.PROMPT, l.step)
        l.workConfirmed() // "it's work" forgets the drift, so the next one starts gently
        l.tick(140, true, true, 0); l.tick(270, true, true, 0)
        assertEquals(Step.PROMPT, l.step)
        l.backToWork(270)
        assertNull(l.tick(400, true, true, 0)); assertNull(l.tick(560, true, true, 0)) // within 5 min grace
        assertNull(l.tick(571, true, true, 0))        // drift restarts after grace
        assertEquals(Step.WINDOW, l.tick(700, true, true, 0)?.step) // within the hour: where it left off
        l.workConfirmed(); assertEquals(Step.NONE, l.step)
    }

    @Test fun brieflyNotWastedDoesNotResetDrift() {
        val l = Ladder()
        l.tick(0, true, true, 0); l.tick(30, false, true, 0); l.tick(40, true, true, 0)
        assertEquals(Step.PROMPT, l.tick(125, true, true, 0)?.step)
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
        assertNull(r.due(sched, st(plan = true), at(12, 30)))                                 // before midpoint − 30 min (12:32)
        assertEquals(Prompt.MIDDAY, r.due(sched, st(plan = true), at(12, 35)))
        assertNull(r.due(sched, st(plan = true, mid = true), at(15)))
    }

    @Test fun middaySkippedWhenDayStartedLate() {
        assertNull(r.due(sched, st(first = at(15, 30), plan = true), at(16, 15)))
    }

    @Test fun eveningComesBeforeWorkEnds() {
        assertNull(r.due(sched, st(plan = true, mid = true), at(16, 29)))
        assertEquals(Prompt.EVENING, r.due(sched, st(plan = true, mid = true), at(16, 30))) // 30 min before 5 PM
        assertEquals(Prompt.EVENING, Rhythm(utc, eveningLeadMin = 60).due(sched, st(plan = true, mid = true), at(16, 0)))
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
