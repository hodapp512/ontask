package dev.ontask.db

import dev.ontask.Category
import dev.ontask.classify.Rule
import dev.ontask.classify.RuleKind
import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkSchedule
import dev.ontask.classify.WorkWeek
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A run of identical consecutive ticks (same app/title/url/classification), stored as one row. */
data class Segment(
    var id: Long = 0,
    val startTs: Long,
    var endTs: Long,
    var n: Int,
    var seconds: Int,
    val app: String?,
    val bundle: String?,
    val title: String?,
    val url: String?,
    val domain: String?,
    val category: Category,
    val source: dev.ontask.Source,
    val ruleId: Long?,
    val workTime: Boolean,
    var skippedIdle: Int = 0,
    var skippedUnchanged: Int = 0,
    var procMs: Long = 0,
    var dirty: Boolean = true,
) {
    val level get() = category.level
}

class TickStore(private val db: Db) {
    /** Inserts new segments and updates existing dirty ones, in one transaction. */
    fun save(segments: List<Segment>) {
        val todo = segments.filter { it.dirty }
        if (todo.isEmpty()) return
        db.tx { c ->
            val ins = c.prepareStatement(
                """INSERT INTO ticks(start_ts,end_ts,n,seconds,app,bundle,title,url,domain,category,level,source,rule_id,work_time,
                   skipped_idle,skipped_unchanged,proc_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            )
            val upd = c.prepareStatement(
                "UPDATE ticks SET end_ts=?,n=?,seconds=?,skipped_idle=?,skipped_unchanged=?,proc_ms=? WHERE id=?",
            )
            for (s in todo) {
                if (s.id == 0L) {
                    var i = 1
                    ins.setLong(i++, s.startTs); ins.setLong(i++, s.endTs); ins.setInt(i++, s.n); ins.setInt(i++, s.seconds)
                    ins.setString(i++, s.app); ins.setString(i++, s.bundle); ins.setString(i++, s.title)
                    ins.setString(i++, s.url); ins.setString(i++, s.domain)
                    ins.setString(i++, s.category.name); ins.setString(i++, s.level.name); ins.setString(i++, s.source.name)
                    if (s.ruleId == null) ins.setNull(i++, java.sql.Types.INTEGER) else ins.setLong(i++, s.ruleId)
                    ins.setInt(i++, if (s.workTime) 1 else 0)
                    ins.setInt(i++, s.skippedIdle); ins.setInt(i++, s.skippedUnchanged); ins.setLong(i, s.procMs)
                    ins.executeUpdate()
                    c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); s.id = it.getLong(1) } }
                } else {
                    upd.setLong(1, s.endTs); upd.setInt(2, s.n); upd.setInt(3, s.seconds)
                    upd.setInt(4, s.skippedIdle); upd.setInt(5, s.skippedUnchanged); upd.setLong(6, s.procMs); upd.setLong(7, s.id)
                    upd.executeUpdate()
                }
                s.dirty = false
            }
            ins.close(); upd.close()
        }
    }
}

class RuleStore(private val db: Db) {
    fun userRules(): List<Rule> = db.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id,kind,pattern,category FROM rules ORDER BY id DESC").use { rs ->
                buildList {
                    while (rs.next()) add(Rule(rs.getLong(1), RuleKind.valueOf(rs.getString(2)), rs.getString(3), Category.valueOf(rs.getString(4)), user = true))
                }
            }
        }
    }

    /** Stores (or replaces) a user rule and records the label for the disagreement metric. */
    fun label(kind: RuleKind, pattern: String, category: Category, previous: Category, now: Long) = db.tx { c ->
        c.prepareStatement("INSERT INTO rules(kind,pattern,category,created_ts) VALUES (?,?,?,?) ON CONFLICT(kind,pattern) DO UPDATE SET category=excluded.category")
            .use { it.setString(1, kind.name); it.setString(2, pattern.trim()); it.setString(3, category.name); it.setLong(4, now); it.executeUpdate() }
        c.prepareStatement("INSERT INTO labels(ts,kind,pattern,previous_category,category) VALUES (?,?,?,?,?)")
            .use { it.setLong(1, now); it.setString(2, kind.name); it.setString(3, pattern.trim()); it.setString(4, previous.name); it.setString(5, category.name); it.executeUpdate() }
    }

    fun delete(id: Long) = db.use { c ->
        c.prepareStatement("DELETE FROM rules WHERE id=?").use { it.setLong(1, id); it.executeUpdate() }
    }
}

data class Settings(
    val tickSec: Int = 5,
    val awayAfterSec: Int = 180,
    val retentionDays: Int = 90,
    val schedule: WorkSchedule = WorkSchedule(),
)

class SettingsStore(private val db: Db) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Settings {
        val m = db.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT key,value FROM settings").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
        }
        val d = Settings()
        val week = m["work_week"]?.let { runCatching { json.decodeFromString<WorkWeek>(it) }.getOrNull() } ?: d.schedule.week
        return Settings(
            tickSec = m["tick_sec"]?.toIntOrNull()?.coerceIn(1, 60) ?: d.tickSec,
            awayAfterSec = m["away_after_sec"]?.toIntOrNull()?.coerceIn(30, 3600) ?: d.awayAfterSec,
            retentionDays = m["retention_days"]?.toIntOrNull()?.coerceIn(1, 3650) ?: d.retentionDays,
            schedule = WorkSchedule(week, exceptions()),
        )
    }

    fun exceptions(): Map<LocalDate, List<TimeRange>> = db.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT date, ranges FROM work_exceptions").use { rs ->
                buildMap { while (rs.next()) put(LocalDate.parse(rs.getString(1)), json.decodeFromString<List<TimeRange>>(rs.getString(2))) }
            }
        }
    }

    fun saveException(date: LocalDate, ranges: List<TimeRange>?) = db.use { c ->
        if (ranges == null) c.prepareStatement("DELETE FROM work_exceptions WHERE date=?").use { it.setString(1, date.toString()); it.executeUpdate() }
        else c.prepareStatement("INSERT INTO work_exceptions(date,ranges) VALUES (?,?) ON CONFLICT(date) DO UPDATE SET ranges=excluded.ranges")
            .use { it.setString(1, date.toString()); it.setString(2, json.encodeToString(ranges)); it.executeUpdate() }
    }

    fun save(s: Settings) = db.tx { c ->
        c.prepareStatement("INSERT INTO settings(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { ps ->
            fun put(k: String, v: String) { ps.setString(1, k); ps.setString(2, v); ps.executeUpdate() }
            put("tick_sec", s.tickSec.toString()); put("away_after_sec", s.awayAfterSec.toString())
            put("retention_days", s.retentionDays.toString()); put("work_week", json.encodeToString(s.schedule.week))
        }
    }
}

/** Free-form string preferences (theme, blocking mode, AI choices, ...). Small; cached in memory. */
class Prefs(private val db: Db) {
    private val cache: MutableMap<String, String> = db.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT key,value FROM settings").use { rs -> HashMap<String, String>().also { while (rs.next()) it[rs.getString(1)] = rs.getString(2) } }
        }
    }

    @Synchronized fun get(key: String): String? = cache[key]
    @Synchronized fun str(key: String, default: String) = cache[key] ?: default
    @Synchronized fun int(key: String, default: Int) = cache[key]?.toIntOrNull() ?: default
    @Synchronized fun bool(key: String, default: Boolean) = cache[key]?.let { it == "1" } ?: default
    @Synchronized fun long(key: String, default: Long) = cache[key]?.toLongOrNull() ?: default

    @Synchronized fun set(key: String, value: String?) {
        if (value == null) { cache.remove(key); db.use { c -> c.prepareStatement("DELETE FROM settings WHERE key=?").use { it.setString(1, key); it.executeUpdate() } }; return }
        cache[key] = value
        db.use { c -> c.prepareStatement("INSERT INTO settings(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { it.setString(1, key); it.setString(2, value); it.executeUpdate() } }
    }
    fun set(key: String, value: Boolean) = set(key, if (value) "1" else "0")
    fun set(key: String, value: Int) = set(key, value.toString())
}
