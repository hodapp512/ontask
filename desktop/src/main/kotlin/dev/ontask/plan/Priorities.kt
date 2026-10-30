package dev.ontask.plan

import dev.ontask.db.Db
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today's priorities (set during planning); shown on the unblock delay screen and in interventions. */
class Priorities(private val db: Db, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun dateOf(now: Long) = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()

    fun today(now: Long): List<String> = forDate(dateOf(now))

    fun forDate(date: LocalDate): List<String> = db.use { c ->
        c.prepareStatement("SELECT text FROM priorities WHERE date=? ORDER BY pos").use { ps ->
            ps.setString(1, date.toString()); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** Most recent day before [date] that had priorities (to pre-fill planning). */
    fun previous(date: LocalDate): List<String> = db.use { c ->
        val d = c.prepareStatement("SELECT MAX(date) FROM priorities WHERE date < ?").use { ps -> ps.setString(1, date.toString()); ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
        if (d == null) emptyList() else forDate(LocalDate.parse(d))
    }

    fun set(date: LocalDate, items: List<String>) = db.tx { c ->
        c.prepareStatement("DELETE FROM priorities WHERE date=?").use { it.setString(1, date.toString()); it.executeUpdate() }
        c.prepareStatement("INSERT INTO priorities(date,pos,text) VALUES (?,?,?)").use { ps ->
            items.filter { it.isNotBlank() }.forEachIndexed { i, t -> ps.setString(1, date.toString()); ps.setInt(2, i); ps.setString(3, t.trim()); ps.executeUpdate() }
        }
    }
}
