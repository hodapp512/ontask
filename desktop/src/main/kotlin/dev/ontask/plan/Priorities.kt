package dev.ontask.plan

import dev.ontask.db.Db
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Each day's plan: tasks you check off ([PlanItem]), set in the morning and changeable all day. Shown on Home, in the
 * check-ins, every nudge and the unblock screen; shared with the phone through users/{uid}/plans/{date} (the newest
 * change wins). [version] changes on every edit, so every window showing the plan redraws.
 */
class Priorities(private val db: Db, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun dateOf(now: Long) = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Called after a change made here (not one taken from the phone), to send it on. */
    @Volatile var onLocalChange: (LocalDate) -> Unit = {}

    init { splitOldPlans() }

    fun today(now: Long): List<String> = forDate(dateOf(now))
    fun itemsToday(now: Long): List<PlanItem> = items(dateOf(now))

    /** The tasks' text, in order (for the AI's context and anything that only needs the words). */
    fun forDate(date: LocalDate): List<String> = items(date).map { it.text }

    fun items(date: LocalDate): List<PlanItem> = db.use { c ->
        c.prepareStatement("SELECT id, text, done FROM priorities WHERE date=? ORDER BY pos").use { ps ->
            ps.setString(1, date.toString())
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(PlanItem(rs.getString(1) ?: Plan.newId(), rs.getString(2), rs.getInt(3) != 0)) } }
        }
    }

    /** The most recent day before [date] that had a plan, and its tasks (yesterday's plan; Friday's on a Monday). */
    fun previous(date: LocalDate): Pair<LocalDate, List<PlanItem>>? {
        val d = db.use { c -> c.prepareStatement("SELECT MAX(date) FROM priorities WHERE date < ?").use { ps -> ps.setString(1, date.toString()); ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } } }
            ?: return null
        val day = LocalDate.parse(d)
        return day to items(day)
    }

    /** When [date]'s list last changed (ms), and whether that change has been sent. */
    fun meta(date: LocalDate): Pair<Long, Boolean>? = db.use { c ->
        c.prepareStatement("SELECT updated_ms, pushed FROM plan_meta WHERE date=?").use { ps -> ps.setString(1, date.toString()); ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to (rs.getInt(2) != 0) else null } }
    }

    fun markPushed(date: LocalDate, updatedMs: Long) = db.use { c ->
        c.prepareStatement("UPDATE plan_meta SET pushed=1 WHERE date=? AND updated_ms=?").use { it.setString(1, date.toString()); it.setLong(2, updatedMs); it.executeUpdate() }
    }

    /** Replaces [date]'s list. [remoteMs]: taken from the phone at that time (not sent back). */
    fun setItems(date: LocalDate, items: List<PlanItem>, remoteMs: Long? = null) {
        db.tx { c ->
            c.prepareStatement("DELETE FROM priorities WHERE date=?").use { it.setString(1, date.toString()); it.executeUpdate() }
            c.prepareStatement("INSERT INTO priorities(date,pos,text,id,done) VALUES (?,?,?,?,?)").use { ps ->
                items.filter { it.text.isNotBlank() }.forEachIndexed { i, t ->
                    ps.setString(1, date.toString()); ps.setInt(2, i); ps.setString(3, t.text.trim()); ps.setString(4, t.id); ps.setInt(5, if (t.done) 1 else 0); ps.executeUpdate()
                }
            }
            c.prepareStatement("INSERT OR REPLACE INTO plan_meta(date, updated_ms, pushed) VALUES (?,?,?)").use {
                it.setString(1, date.toString()); it.setLong(2, remoteMs ?: System.currentTimeMillis()); it.setInt(3, if (remoteMs != null) 1 else 0); it.executeUpdate()
            }
        }
        _version.value++
        if (remoteMs == null) runCatching { onLocalChange(date) }
    }

    /** An old free-form plan (or one from an older phone): split into tasks by line and by sentence. */
    fun set(date: LocalDate, text: List<String>) = setItems(date, Plan.fromText(text.joinToString("\n")))

    fun add(date: LocalDate, text: String) { if (text.isNotBlank()) setItems(date, items(date) + PlanItem(Plan.newId(), text.trim())) }
    fun toggle(date: LocalDate, id: String) = setItems(date, items(date).map { if (it.id == id) it.copy(done = !it.done) else it })
    fun edit(date: LocalDate, id: String, text: String) =
        setItems(date, if (text.isBlank()) items(date).filter { it.id != id } else items(date).map { if (it.id == id) it.copy(text = text.trim()) else it })
    fun remove(date: LocalDate, id: String) = setItems(date, items(date).filter { it.id != id })
    fun move(date: LocalDate, from: Int, to: Int) {
        val l = items(date).toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        l.add(to, l.removeAt(from)); setItems(date, l)
    }

    /** Once: plans saved as one block of text (before tasks) become one task per line and per sentence. */
    private fun splitOldPlans() = runCatching {
        val old = db.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT date, text FROM priorities WHERE id IS NULL ORDER BY date, pos").use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } } } }
        old.groupBy({ it.first }, { it.second }).forEach { (d, texts) ->
            val items = Plan.fromText(texts.joinToString("\n"))
            db.tx { c ->
                c.prepareStatement("DELETE FROM priorities WHERE date=?").use { it.setString(1, d); it.executeUpdate() }
                c.prepareStatement("INSERT INTO priorities(date,pos,text,id,done) VALUES (?,?,?,?,0)").use { ps ->
                    items.forEachIndexed { i, t -> ps.setString(1, d); ps.setInt(2, i); ps.setString(3, t.text); ps.setString(4, t.id); ps.executeUpdate() }
                }
            }
        }
    }
}
