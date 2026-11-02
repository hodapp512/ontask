package dev.ontask.report

import dev.ontask.Level

/**
 * A stretch of time in one category (adjacent segments of the same category merged), with seconds per app, site or
 * video in it ("youtube.com – Learning Java"). Its color comes from [level]; [category] is shown when you hover.
 */
/**
 * What a row of [Run.activity] is, for changing its category: the app or site a rule would match, and for a page or
 * video on a Varies site its item key and title.
 */
data class ItemRef(val subject: String, val itemKey: String? = null, val title: String? = null)

data class Run(val start: Long, val end: Long, val level: Level, val activity: Map<String, Long> = emptyMap(), val category: dev.ontask.Category? = null, val refs: Map<String, ItemRef> = emptyMap()) {
    val seconds get() = end - start
    /** The [n] apps or sites that took the most time in this stretch. */
    fun top(n: Int = 3): List<Pair<String, Long>> = activity.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
}

data class DeepSession(val start: Long, val end: Long) {
    val seconds get() = end - start
}

object Sessions {
    const val MIN_DEEP_SEC = 25 * 60L
    const val MIN_DISTRACTION_SEC = 30L
    const val MAX_GAP_SEC = 60L

    /** Merge consecutive same-level runs separated by at most [maxGap] seconds. Input must be ordered by start. */
    fun merge(raw: List<Run>, maxGap: Long = 15): List<Run> {
        val out = ArrayList<Run>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && last.level == r.level && last.category == r.category && r.start - last.end <= maxGap) {
                val act = HashMap(last.activity); r.activity.forEach { (k, v) -> act.merge(k, v, Long::plus) }
                out[out.lastIndex] = last.copy(end = maxOf(last.end, r.end), activity = act, refs = last.refs + r.refs)
            }
            else out.add(r)
        }
        return out
    }

    /**
     * Focused session: an uninterrupted stretch of DEEP/MIXED time (no WASTED, no AWAY, no gap over a minute)
     * lasting 25+ minutes that includes some deep focus.
     */
    fun deepSessions(runs: List<Run>): List<DeepSession> {
        val out = ArrayList<DeepSession>()
        var start = -1L; var end = -1L; var hasDeep = false
        fun close() {
            if (start >= 0 && hasDeep && end - start >= MIN_DEEP_SEC) out.add(DeepSession(start, end))
            start = -1; hasDeep = false
        }
        for (r in runs) {
            val good = r.level == Level.DEEP || r.level == Level.MIXED
            if (start >= 0 && r.start - end > MAX_GAP_SEC) close()
            if (!good) { close(); continue }
            if (start < 0) start = r.start
            end = r.end
            if (r.level == Level.DEEP) hasDeep = true
        }
        close()
        return out
    }

    /** Distraction event: a WASTED run of at least 30 seconds. */
    fun distractionEvents(runs: List<Run>): Int = runs.count { it.level == Level.WASTED && it.seconds >= MIN_DISTRACTION_SEC }
}

/** What a stretch was: its category ("Entertainment / News / Social"), "Unsure" for undecided pages, or the level. */
fun runLabel(run: Run): String = when {
    run.level == Level.AWAY -> Level.AWAY.label
    run.category == null || run.category == dev.ontask.Category.UNKNOWN -> run.level.label
    run.category.variable -> "Unsure"
    else -> run.category.label
}
