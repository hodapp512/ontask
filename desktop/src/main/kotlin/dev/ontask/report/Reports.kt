package dev.ontask.report

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.db.Db
import java.time.LocalDate
import java.time.ZoneId

/** A row of "where the time went": an app, a site, or a page or video on a Varies site ("YouTube: …"). */
data class Subject(val name: String, val category: Category, val seconds: Long, val itemKey: String? = null, val title: String? = null) {
    /** A page or video on a Varies site: its dropdown changes just this one. Its category is Unsure (Varies) while undecided. */
    val isItem get() = itemKey != null
}

data class DailyReport(
    val date: LocalDate,
    val dayStart: Long,
    val totalsAll: Map<Level, Long>,
    val totalsWork: Map<Level, Long>,
    val timeline: List<Run>,
    val deepSessions: List<DeepSession>,
    val distractionEvents: Int,
    val worstHour: Pair<Int, Long>?,
    val topSubjects: List<Subject>,
)

/** All report numbers are computed on demand from SQL; nothing is cached. */
class Reports(private val db: Db, private val zone: ZoneId = ZoneId.systemDefault()) {

    // Raw ticks and rolled-up hours, as one relation.
    private val unionSql = """
        SELECT start_ts AS ts, level, COALESCE(item_cat, category) AS category, work_time, COALESCE(item, domain, app, '') AS subject, seconds,
               CASE WHEN item IS NOT NULL THEN app END AS i_app, CASE WHEN item IS NOT NULL THEN title END AS i_title, CASE WHEN item IS NOT NULL THEN url END AS i_url FROM ticks
        UNION ALL
        SELECT hour_ts, level, category, work_time, subject, seconds, NULL, NULL, NULL FROM hourly
    """

    fun daily(date: LocalDate): DailyReport {
        val start = date.atStartOfDay(zone).toEpochSecond()
        val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        return db.use { c ->
            val all = HashMap<Level, Long>(); val work = HashMap<Level, Long>()
            c.prepareStatement("SELECT level, work_time, SUM(seconds) FROM ($unionSql) WHERE ts >= ? AND ts < ? GROUP BY level, work_time").use { ps ->
                ps.setLong(1, start); ps.setLong(2, end)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val l = Level.valueOf(rs.getString(1)); val s = rs.getLong(3)
                        all.merge(l, s, Long::plus)
                        if (rs.getInt(2) == 1) work.merge(l, s, Long::plus)
                    }
                }
            }

            val raw = ArrayList<Run>()
            c.prepareStatement("SELECT start_ts, end_ts, level, COALESCE(domain, app, '') FROM ticks WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts").use { ps ->
                ps.setLong(1, start); ps.setLong(2, end)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val a = rs.getLong(1); val b = rs.getLong(2); val subject = rs.getString(4)
                        raw.add(Run(a, b, Level.valueOf(rs.getString(3)), if (subject.isBlank()) emptyMap() else mapOf(dev.ontask.track.displayAppName(subject) to b - a)))
                    }
                }
            }
            val timeline = Sessions.merge(raw)

            // Worst hour: most wasted time, preferring work time when there is any.
            val wasted = HashMap<Pair<Int, Boolean>, Long>()
            c.prepareStatement("SELECT (ts - ?) / 3600, work_time, SUM(seconds) FROM ($unionSql) WHERE ts >= ? AND ts < ? AND level = 'WASTED' GROUP BY 1, 2").use { ps ->
                ps.setLong(1, start); ps.setLong(2, start); ps.setLong(3, end)
                ps.executeQuery().use { rs -> while (rs.next()) wasted.merge(rs.getInt(1) to (rs.getInt(2) == 1), rs.getLong(3), Long::plus) }
            }
            val byHour = HashMap<Int, Long>()
            val workOnly = wasted.filterKeys { it.second }
            (if (workOnly.isNotEmpty()) workOnly else wasted).forEach { (k, v) -> byHour.merge(k.first, v, Long::plus) }
            val worst = byHour.maxByOrNull { it.value }?.let { it.key to it.value }

            val subjects = ArrayList<Subject>()
            c.prepareStatement(
                """SELECT subject, category, SUM(seconds) s, MAX(i_app), MAX(i_title), MAX(i_url) FROM ($unionSql) WHERE ts >= ? AND ts < ? AND level != 'AWAY' AND subject != ''
                   GROUP BY subject, category ORDER BY s DESC LIMIT 40""",
            ).use { ps ->
                ps.setLong(1, start); ps.setLong(2, end)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val url = rs.getString(6); val title = rs.getString(5)
                        val key = if (url != null || title != null) dev.ontask.classify.itemKey(rs.getString(4), title, url) else null
                        subjects.add(Subject(if (key != null) rs.getString(1) else dev.ontask.track.displayAppName(rs.getString(1)), Category.valueOf(rs.getString(2)), rs.getLong(3), key, title))
                    }
                }
            }
            // Names that display the same (e.g. "MainKt" and "OnTask") become one row.
            val merged = subjects.groupBy { it.name to it.category }.map { (k, v) -> Subject(k.first, k.second, v.sumOf { it.seconds }, v.firstNotNullOfOrNull { it.itemKey }, v.firstNotNullOfOrNull { it.title }) }
                .sortedByDescending { it.seconds }.take(20)
            subjects.clear(); subjects.addAll(merged)

            DailyReport(date, start, all, work, timeline, Sessions.deepSessions(timeline), Sessions.distractionEvents(timeline), worst, subjects)
        }
    }

    /** First non-away moment of [date] (the anchor of the daily rhythm), and the most recent one. */
    fun presence(date: LocalDate): Pair<Long?, Long?> {
        val start = date.atStartOfDay(zone).toEpochSecond(); val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        return db.use { c ->
            c.prepareStatement("SELECT MIN(start_ts), MAX(end_ts) FROM ticks WHERE level != 'AWAY' AND start_ts >= ? AND start_ts < ?").use { ps ->
                ps.setLong(1, start); ps.setLong(2, end)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1).takeIf { !rs.wasNull() } to rs.getLong(2).takeIf { !rs.wasNull() } }
            }
        }
    }

    /** Today's totals as the tracker's in-memory counters want them. */
    fun totals(date: LocalDate): Map<Level, Long> = daily(date).totalsAll
}
