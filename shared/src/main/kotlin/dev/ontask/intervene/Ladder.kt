package dev.ontask.intervene

data class LadderConfig(
    val step1WastedSec: Int = 120,        // 2 min continuous "wasted"
    val step2ExtraSec: Int = 180,         // +3 min drift
    val step3ExtraSec: Int = 300,         // +5 min drift
    val unblocksStep1: Int = 2,           // 2nd unblock within the window
    val unblocksStep2: Int = 3,
    val unblocksStep3: Int = 4,
    val unblockWindowSec: Int = 3600,
    val graceSec: Int = 300,              // "Back to work" grace
    val resumeWithinSec: Int = 3600,      // drifting again within this long picks up where the last drift left off
    val lockSec: Int = 30,                // step-2 window can't be dismissed for this long (30s..3min)
    val alertsPerDay: Int = 2,            // partner binge alerts rate limit (1-2/day)
    val wastedBreakSec: Int = 60,         // this long off "wasted" ends the run
)

enum class Step(val n: Int) { NONE(0), PROMPT(1), WINDOW(2), PARTNER(3) }

/**
 * [partnerAllowed]: false before work hours, when step 3 doesn't offer to message the partner. The rest is filled in by
 * the caller from [Breaks]: [breakSec] > 0 makes step 1 the "Is this a break?" check; [focusedSec] and [toEarnSec] are
 * focus since the last break and what's left to earn one; [lunch] offers Lunch; break totals for the last hour and today.
 */
data class Intervention(
    val step: Step, val driftSec: Long, val unblocks: Int, val partnerAllowed: Boolean = true, val site: String? = null,
    val breakSec: Long = 0, val focusedSec: Long = 0, val toEarnSec: Long = 0, val lunch: Boolean = false,
    val breaksHourSec: Long = 0, val breaksTodaySec: Long = 0,
)

/**
 * The binge ladder. Feed it one observation per tick; it says when to escalate. Pure state machine: no clocks, no UI.
 * Active only during work hours and Focus sessions (the caller passes [active]).
 */
class Ladder(var cfg: LadderConfig = LadderConfig()) {
    var step = Step.NONE; private set
    private var wastedSince: Long? = null
    private var lastWasted = 0L
    private var graceUntil = 0L
    /** The highest step the last drift reached, and when it ended: a new drift within the hour starts one step higher. */
    private var lastPeak = 0
    private var lastEnd = 0L
    /** Set when a break or lunch ends: still drifting a minute later goes straight to step 2. */
    private var afterBreak = false
    private var afterBreakTs = 0L

    val graceActive get() = graceUntil > 0

    fun tick(now: Long, wasted: Boolean, active: Boolean, unblocksInWindow: Int): Intervention? {
        if (now < graceUntil) { wastedSince = null; return null }
        if (!active) { reset(); return null }
        if (wasted) { if (wastedSince == null) wastedSince = now; lastWasted = now }
        else if (wastedSince != null && now - lastWasted >= cfg.wastedBreakSec) { endRun(now); wastedSince = null; afterBreak = false; if (unblocksInWindow < cfg.unblocksStep1) step = Step.NONE }

        // Back at work a minute after the break: it's over, and the next drift starts gently again.
        if (afterBreak && wastedSince == null && now - afterBreakTs >= cfg.wastedBreakSec) afterBreak = false
        val drift = wastedSince?.let { now - it } ?: 0L
        var target = 0
        if (drift >= cfg.step1WastedSec) target = 1
        if (drift >= cfg.step1WastedSec + cfg.step2ExtraSec) target = 2
        if (drift >= cfg.step1WastedSec + cfg.step2ExtraSec + cfg.step3ExtraSec) target = 3
        // Drifting again soon after: start where the last drift left off (second drift at step 2, third at step 3).
        if (target > 0 && lastPeak > 0 && now - lastEnd <= cfg.resumeWithinSec) target = minOf(3, target + lastPeak)
        if (afterBreak && drift >= 60) target = maxOf(target, 2)
        if (unblocksInWindow >= cfg.unblocksStep1) target = maxOf(target, 1)
        if (unblocksInWindow >= cfg.unblocksStep2) target = maxOf(target, 2)
        if (unblocksInWindow >= cfg.unblocksStep3) target = maxOf(target, 3)

        if (target > step.n) { step = Step.entries[target]; return Intervention(step, drift, unblocksInWindow) }
        return null
    }

    /** "Yes, it's work": resets the ladder, and forgets earlier drifts (the caller also creates a rule). */
    fun workConfirmed() { reset(); lastPeak = 0; afterBreak = false }

    /** A break or lunch is over: drifting on afterwards escalates at once. */
    fun breakOver(now: Long) { reset(); afterBreak = true; afterBreakTs = now }

    /** "Back to work" (or blocking the site): a short grace, then a new drift picks up where this one left off. */
    fun backToWork(now: Long) { endRun(now); reset(); afterBreak = false; graceUntil = now + cfg.graceSec }

    private fun endRun(now: Long) {
        if (step.n == 0) return
        lastPeak = if (now - lastEnd <= cfg.resumeWithinSec) maxOf(lastPeak, step.n) else step.n
        lastEnd = now
    }

    /** "I'm struggling": jump to the partner step. */
    fun struggling(): Intervention? { if (step == Step.PARTNER) return null; step = Step.PARTNER; return Intervention(step, 0, 0) }

    fun reset() { step = Step.NONE; wastedSince = null; graceUntil = 0 }
}

/** Partner binge alerts are rate-limited per day; extras are queued into the next check-in. */
class AlertLimiter(private val perDay: Int) {
    private val sent = HashMap<String, Int>()
    private val queued = ArrayList<String>()

    /** True if it may be sent now; otherwise it is queued. */
    fun offer(dayKey: String, message: String): Boolean {
        val n = sent.getOrDefault(dayKey, 0)
        if (n < perDay) { sent[dayKey] = n + 1; return true }
        queued.add(message); return false
    }

    fun drainQueued(): List<String> = queued.toList().also { queued.clear() }
}
