package dev.ontask.intervene

import dev.ontask.Level

/**
 * Breaks are earned by focused work. Focused minutes since your last break add up; minutes that aren't focused (Mixed
 * or Wasted) are taken off again minute for minute, never below zero, once a stretch of them lasts a minute (shorter
 * dips are ignored). 5+ minutes away, a break, or lunch starts the count again.
 *
 * Earned: nothing under 25 minutes; 5 minutes per 25 focused, at most 10 under an hour and 15 after.
 *
 * A break ends when its time is up (the caller shows "That's 5 minutes. Back to work?") or quietly after 2 minutes of
 * focused work. Lunch (11:00–2:00, once a day): "Is lunch over?" at 30 minutes with 15 more allowed, over at 45;
 * working during lunch doesn't end it. Pure state machine: the caller passes the time and what you're doing.
 */
class Breaks(
    private val lunchCheckSec: Long = 30 * 60L,
    private val lunchMaxSec: Long = 45 * 60L,
) {
    enum class Kind { BREAK, LUNCH }
    data class Active(val kind: Kind, val start: Long, val until: Long, val extended: Boolean = false, val asked: Boolean = false)

    /** What the caller should show: a break ran out, or lunch reached its check (30 min) or its end (45 min). */
    enum class Due { BREAK_OVER, LUNCH_CHECK, LUNCH_OVER }

    var focusedSec = 0L; private set
    var active: Active? = null; private set
    var lunchDay: String? = null; private set
    private var nonFocusRun = 0L
    private var awayRun = 0L
    private var focusedInBreak = 0L
    /** Breaks taken (not lunch), for "14 min of breaks in the last hour (35 today)". */
    private val taken = ArrayList<Pair<Long, Long>>()

    fun earnedSec(): Long {
        val m = focusedSec / 60
        if (m < 25) return 0
        val cap = if (m < 60) 10 else 15
        return minOf(cap.toLong(), (m / 25) * 5) * 60
    }

    /** Focused minutes still needed for the first 5-minute break. */
    fun toEarnSec(): Long = (25 * 60L - focusedSec).coerceAtLeast(0)

    fun onBreak() = active != null

    fun breakSec(now: Long, sinceTs: Long): Long = taken.sumOf { (a, b) -> (minOf(b, now) - maxOf(a, sinceTs)).coerceAtLeast(0) } +
        (active?.takeIf { it.kind == Kind.BREAK }?.let { (minOf(now, it.until) - maxOf(it.start, sinceTs)).coerceAtLeast(0) } ?: 0)

    /** One tick of [dt] seconds; [level] null = away. Returns what to show, if anything. */
    fun tick(now: Long, dt: Long, level: Level?): Due? {
        val a = active
        if (a != null) {
            if (level == Level.DEEP) focusedInBreak += dt else if (level != null) focusedInBreak = 0
            when (a.kind) {
                Kind.BREAK -> {
                    if (focusedInBreak >= 120) { end(now); return null } // back at work: ends quietly
                    if (now >= a.until) { end(now); return Due.BREAK_OVER }
                }
                Kind.LUNCH -> {
                    if (now - a.start >= lunchMaxSec) { end(now); return Due.LUNCH_OVER }
                    if (!a.asked && now - a.start >= lunchCheckSec) { active = a.copy(asked = true); return Due.LUNCH_CHECK }
                }
            }
            return null
        }
        when (level) {
            null -> { awayRun += dt; nonFocusRun = 0; if (awayRun >= 300) focusedSec = 0 }
            Level.DEEP -> { awayRun = 0; nonFocusRun = 0; focusedSec += dt }
            else -> {
                awayRun = 0
                nonFocusRun += dt
                // The first minute of a stretch is forgiven until it lasts a minute; then all of it counts.
                if (nonFocusRun >= 60) focusedSec = (focusedSec - if (nonFocusRun - dt < 60) nonFocusRun else dt).coerceAtLeast(0)
            }
        }
        return null
    }

    /** "Yes, 5-min break": the earned length. */
    fun startBreak(now: Long) { val e = earnedSec(); if (e > 0) begin(Active(Kind.BREAK, now, now + e)) }

    /** Lunch: offered 11:00–2:00 ([minuteOfDay]), once on [day]. */
    fun lunchOffered(day: String, minuteOfDay: Int) = lunchDay != day && minuteOfDay in 11 * 60 until 14 * 60
    fun startLunch(now: Long, day: String) { lunchDay = day; begin(Active(Kind.LUNCH, now, now + lunchMaxSec)) }

    /** "5 more minutes" after a break (once), or "15 more minutes" of lunch. */
    fun extend(now: Long): Boolean {
        val last = taken.lastOrNull()
        return when {
            canExtend(now) && last != null -> {
                taken.removeAt(taken.lastIndex); begin(Active(Kind.BREAK, last.first, now + 300, extended = true)); true
            }
            active?.kind == Kind.LUNCH -> { active = active!!.copy(asked = true); true } // lunch runs on to 45 min
            else -> false
        }
    }

    fun lastTaken(): Pair<Long, Long>? = taken.lastOrNull()
    /** "5 more minutes" is allowed once per break, soon after it ends. */
    fun canExtend(now: Long) = lastKind == Kind.BREAK && !lastExtended && taken.lastOrNull()?.let { now - it.second < 600 } == true

    /** "Back to work" from a break or lunch card. */
    fun end(now: Long) {
        val a = active ?: return
        if (a.kind == Kind.BREAK) taken += a.start to minOf(now, a.until)
        lastKind = a.kind; lastExtended = a.extended
        active = null; focusedSec = 0; nonFocusRun = 0; focusedInBreak = 0
    }
    private var lastKind: Kind? = null
    private var lastExtended = false

    private fun begin(a: Active) { active = a; focusedInBreak = 0 }

    /** A new day: yesterday's breaks don't count toward today's totals. */
    fun forgetBefore(ts: Long) { taken.removeAll { it.second < ts } }
}
