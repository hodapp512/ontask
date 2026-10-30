package dev.ontask.report

import dev.ontask.Level

/** A stretch of time at one level (adjacent same-level segments merged). */
data class Run(val start: Long, val end: Long, val level: Level) {
    val seconds get() = end - start
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
            if (last != null && last.level == r.level && r.start - last.end <= maxGap) out[out.lastIndex] = last.copy(end = maxOf(last.end, r.end))
            else out.add(r)
        }
        return out
    }

    /**
     * Deep-work session: an uninterrupted stretch of DEEP/MIXED time (no WASTED, no AWAY, no gap over a minute)
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
