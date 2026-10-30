package dev.ontask.report

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Source
import dev.ontask.db.Db

data class LogRow(
    val ts: Long, val seconds: Int, val app: String?, val domain: String?, val title: String?,
    val category: Category, val level: Level, val source: Source, val workTime: Boolean,
)

data class LogFilter(val text: String = "", val level: Level? = null, val source: Source? = null, val limit: Int = 200)

data class DiagStats(
    val sourceTicks: Map<Source, Long>,
    val skippedIdle: Long,
    val skippedUnchanged: Long,
    val totalTicks: Long,
    val avgProcMs: Double,
    val rawRows: Long,
    val hourlyRows: Long,
    val oldestRaw: Long?,
    val labels: Long,
    val disagreements: Long,
    val dbBytes: Long?,
)

class Diagnostics(private val db: Db) {

    fun stats(): DiagStats = db.use { c ->
        fun scalar(sql: String): Long = c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        val src = HashMap<Source, Long>()
        c.createStatement().use { st ->
            st.executeQuery("SELECT source, SUM(t) FROM (SELECT source, SUM(n) t FROM ticks GROUP BY source UNION ALL SELECT source, SUM(ticks) FROM hourly GROUP BY source) GROUP BY source").use { rs ->
                while (rs.next()) src[Source.valueOf(rs.getString(1))] = rs.getLong(2)
            }
        }
        val total = src.values.sum()
        val proc = scalar("SELECT COALESCE(SUM(p),0) FROM (SELECT SUM(proc_ms) p FROM ticks UNION ALL SELECT SUM(proc_ms) FROM hourly)")
        DiagStats(
            sourceTicks = src,
            skippedIdle = scalar("SELECT COALESCE(SUM(s),0) FROM (SELECT SUM(skipped_idle) s FROM ticks UNION ALL SELECT SUM(skipped_idle) FROM hourly)"),
            skippedUnchanged = scalar("SELECT COALESCE(SUM(s),0) FROM (SELECT SUM(skipped_unchanged) s FROM ticks UNION ALL SELECT SUM(skipped_unchanged) FROM hourly)"),
            totalTicks = total,
            avgProcMs = if (total > 0) proc.toDouble() / total else 0.0,
            rawRows = scalar("SELECT COUNT(*) FROM ticks"),
            hourlyRows = scalar("SELECT COUNT(*) FROM hourly"),
            oldestRaw = scalar("SELECT COALESCE(MIN(start_ts),0) FROM ticks").takeIf { it > 0 },
            labels = scalar("SELECT COUNT(*) FROM labels"),
            disagreements = scalar("SELECT COUNT(*) FROM labels WHERE previous_category != category"),
            dbBytes = db.path?.let { p -> runCatching { java.nio.file.Files.size(p) }.getOrNull() },
        )
    }

    fun log(f: LogFilter): List<LogRow> = db.use { c ->
        val where = StringBuilder("1=1"); val args = ArrayList<Any>()
        if (f.text.isNotBlank()) {
            where.append(" AND (app LIKE ? OR domain LIKE ? OR title LIKE ?)")
            repeat(3) { args.add("%${f.text.trim()}%") }
        }
        f.level?.let { where.append(" AND level = ?"); args.add(it.name) }
        f.source?.let { where.append(" AND source = ?"); args.add(it.name) }
        c.prepareStatement("SELECT start_ts,seconds,app,domain,title,category,level,source,work_time FROM ticks WHERE $where ORDER BY start_ts DESC LIMIT ${f.limit}").use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a as String) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        LogRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            Category.valueOf(rs.getString(6)), Level.valueOf(rs.getString(7)), Source.valueOf(rs.getString(8)), rs.getInt(9) == 1),
                    )
                }
            }
        }
    }
}
