package dev.ontask.intervene

data class LadderConfig(
    val step1WastedSec: Int = 180,        // 3 min continuous "wasted"
    val step2ExtraSec: Int = 300,         // +5 min drift
    val step3ExtraSec: Int = 600,         // +10 min drift
    val unblocksStep1: Int = 2,           // 2nd unblock within the window
    val unblocksStep2: Int = 3,
    val unblocksStep3: Int = 4,
    val unblockWindowSec: Int = 3600,
    val graceSec: Int = 600,              // "Back to work" grace
    val lockSec: Int = 30,                // step-2 window can't be dismissed for this long (30s..3min)
    val alertsPerDay: Int = 2,            // partner binge alerts rate limit (1-2/day)
    val wastedBreakSec: Int = 60,         // this long off "wasted" ends the run
)

enum class Step(val n: Int) { NONE(0), PROMPT(1), WINDOW(2), PARTNER(3) }

data class Intervention(val step: Step, val driftSec: Long, val unblocks: Int)

/**
 * The binge ladder. Feed it one observation per tick; it says when to escalate. Pure state machine: no clocks, no UI.
 * Active only during work hours and Focus sessions (the caller passes [active]).
 */
class Ladder(var cfg: LadderConfig = LadderConfig()) {
    var step = Step.NONE; private set
    private var wastedSince: Long? = null
    private var lastWasted = 0L
    private var graceUntil = 0L

    val graceActive get() = graceUntil > 0

    fun tick(now: Long, wasted: Boolean, active: Boolean, unblocksInWindow: Int): Intervention? {
        if (now < graceUntil) { wastedSince = null; return null }
        if (!active) { reset(); return null }
        if (wasted) { if (wastedSince == null) wastedSince = now; lastWasted = now }
        else if (wastedSince != null && now - lastWasted >= cfg.wastedBreakSec) { wastedSince = null; if (unblocksInWindow < cfg.unblocksStep1) step = Step.NONE }

        val drift = wastedSince?.let { now - it } ?: 0L
        var target = 0
        if (drift >= cfg.step1WastedSec) target = 1
        if (drift >= cfg.step1WastedSec + cfg.step2ExtraSec) target = 2
        if (drift >= cfg.step1WastedSec + cfg.step2ExtraSec + cfg.step3ExtraSec) target = 3
        if (unblocksInWindow >= cfg.unblocksStep1) target = maxOf(target, 1)
        if (unblocksInWindow >= cfg.unblocksStep2) target = maxOf(target, 2)
        if (unblocksInWindow >= cfg.unblocksStep3) target = maxOf(target, 3)

        if (target > step.n) { step = Step.entries[target]; return Intervention(step, drift, unblocksInWindow) }
        return null
    }

    /** "Yes, it's work": resets the ladder (the caller also creates a rule). */
    fun workConfirmed() = reset()

    /** "Back to work": 10-minute grace, then the ladder starts over. */
    fun backToWork(now: Long) { reset(); graceUntil = now + cfg.graceSec }

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
