package dev.ontask.db

/** Raw ticks older than the retention window are folded into per-hour summaries (kept forever), then deleted. */
object Retention {
    /** Returns the number of raw rows rolled up. Idempotent: rows are deleted in the same transaction. */
    fun rollup(db: Db, nowSec: Long, retentionDays: Int): Int {
        val cutoff = nowSec - retentionDays * 86_400L
        val n = db.tx { c ->
            c.prepareStatement(
                """INSERT INTO hourly(hour_ts,level,category,source,work_time,subject,seconds,ticks,skipped_idle,skipped_unchanged,proc_ms)
                   SELECT (start_ts/3600)*3600, level, category, source, work_time, COALESCE(domain, app, ''),
                          SUM(seconds), SUM(n), SUM(skipped_idle), SUM(skipped_unchanged), SUM(proc_ms)
                   FROM ticks WHERE end_ts < ? GROUP BY 1,2,3,4,5,6
                   ON CONFLICT(hour_ts,level,category,source,work_time,subject) DO UPDATE SET
                     seconds = seconds + excluded.seconds, ticks = ticks + excluded.ticks,
                     skipped_idle = skipped_idle + excluded.skipped_idle,
                     skipped_unchanged = skipped_unchanged + excluded.skipped_unchanged,
                     proc_ms = proc_ms + excluded.proc_ms""",
            ).use { it.setLong(1, cutoff); it.executeUpdate() }
            c.prepareStatement("DELETE FROM ticks WHERE end_ts < ?").use { it.setLong(1, cutoff); it.executeUpdate() }
        }
        if (n > 0) db.use { c -> c.createStatement().use { it.execute("PRAGMA incremental_vacuum") } }
        return n
    }
}
