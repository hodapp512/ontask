package dev.ontask.report

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.db.Db
import java.time.LocalDate
import java.time.ZoneId

data class Subject(val name: String, val category: Category, val seconds: Long)

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
        SELECT start_ts AS ts, level, category, work_time, COALESCE(domain, app, '') AS subject, seconds FROM ticks
        UNION ALL
        SELECT hour_ts, level, category, work_time, subject, seconds FROM hourly
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
            c.prepareStatement("SELECT start_ts, end_ts, level FROM ticks WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts").use { ps ->
                ps.setLong(1, start); ps.setLong(2, end)
                ps.executeQuery().use { rs -> while (rs.next()) raw.add(Run(rs.getLong(1), rs.getLong(2), Level.valueOf(rs.getString(3)))) }
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
                """SELECT subject, category, SUM(seconds) s FROM ($unionSql) WHERE ts >= ? AND ts < ? AND level != 'AWAY' AND subject != ''
                   GROUP BY subject, category ORDER BY s DESC LIMIT 10""",
            ).use { ps ->
                ps.setLong(1, start); ps.setLong(2, end)
                ps.executeQuery().use { rs -> while (rs.next()) subjects.add(Subject(rs.getString(1), Category.valueOf(rs.getString(2)), rs.getLong(3))) }
            }

            DailyReport(date, start, all, work, timeline, Sessions.deepSessions(timeline), Sessions.distractionEvents(timeline), worst, subjects)
        }
    }

    /** Today's totals as the tracker's in-memory counters want them. */
    fun totals(date: LocalDate): Map<Level, Long> = daily(date).totalsAll
}
