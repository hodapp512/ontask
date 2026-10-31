package dev.ontask.plan

import dev.ontask.Level
import dev.ontask.db.Db
import dev.ontask.report.DailyReport
import java.time.LocalDate

data class Checkin(val date: LocalDate, val kind: String, val ts: Long, val rating: Int?, val helped: String?, val hurt: String?, val change: String?, val message: String?, val sent: Boolean)

class Checkins(private val db: Db) {
    fun save(c: Checkin) = db.use { conn ->
        conn.prepareStatement(
            """INSERT INTO checkins(date,kind,ts,rating,helped,hurt,change_tomorrow,message,sent) VALUES (?,?,?,?,?,?,?,?,?)
               ON CONFLICT(date,kind) DO UPDATE SET ts=excluded.ts, rating=excluded.rating, helped=excluded.helped, hurt=excluded.hurt,
               change_tomorrow=excluded.change_tomorrow, message=excluded.message, sent=excluded.sent""",
        ).use {
            it.setString(1, c.date.toString()); it.setString(2, c.kind); it.setLong(3, c.ts)
            if (c.rating == null) it.setNull(4, java.sql.Types.INTEGER) else it.setInt(4, c.rating)
            it.setString(5, c.helped); it.setString(6, c.hurt); it.setString(7, c.change); it.setString(8, c.message); it.setInt(9, if (c.sent) 1 else 0); it.executeUpdate()
        }
    }

    fun get(date: LocalDate, kind: String): Checkin? = db.use { conn ->
        conn.prepareStatement("SELECT date,kind,ts,rating,helped,hurt,change_tomorrow,message,sent FROM checkins WHERE date=? AND kind=?").use { ps ->
            ps.setString(1, date.toString()); ps.setString(2, kind)
            ps.executeQuery().use { rs -> if (rs.next()) Checkin(LocalDate.parse(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getInt(4).takeIf { !rs.wasNull() }, rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9) == 1) else null }
        }
    }

    fun done(date: LocalDate, kind: String) = get(date, kind) != null

    /** (date, rating) for the last [days] days that have an evening rating. */
    fun ratings(until: LocalDate, days: Int): List<Pair<LocalDate, Int>> = db.use { conn ->
        conn.prepareStatement("SELECT date, rating FROM checkins WHERE kind='evening' AND rating IS NOT NULL AND date <= ? AND date > ? ORDER BY date").use { ps ->
            ps.setString(1, until.toString()); ps.setString(2, until.minusDays(days.toLong()).toString())
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(LocalDate.parse(rs.getString(1)) to rs.getInt(2)) } }
        }
    }
}

object CheckinDraft {
    /** How the day felt, stored as 1–5. */
    val FEELINGS = listOf(1 to "😣 Rough", 2 to "😕 Meh", 3 to "🙂 Okay", 4 to "😊 Good", 5 to "🤩 Great")
    fun feeling(rating: Int): String = FEELINGS.firstOrNull { it.first == rating }?.second ?: "$rating/5"

    /** First line the partner reads before the message; the server adds it when delivering. */
    fun intro(fullName: String?): String? = fullName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "Here is $it's evening review." }

    fun deepShare(r: DailyReport?): Int? {
        r ?: return null
        val denom = Level.entries.filter { it != Level.AWAY }.sumOf { r.totalsAll[it] ?: 0 }
        return if (denom < 600) null else ((r.totalsAll[Level.DEEP] ?: 0) * 100 / denom).toInt()
    }

    private fun dur(sec: Long) = if (sec >= 3600) "${sec / 3600}h ${sec % 3600 / 60}m" else "${sec / 60}m"

    /** The drafted check-in the user reviews before it is sent to their partner. */
    fun message(today: DailyReport, yesterday: DailyReport?, rating: Int?, helped: String?, hurt: String?, change: String?, struggles: List<String> = emptyList()): String = buildString {
        val share = deepShare(today); val prev = deepShare(yesterday)
        append("Today: ${dur(today.totalsAll[Level.DEEP] ?: 0)} deep focus, ${dur(today.totalsAll[Level.WASTED] ?: 0)} wasted")
        if (share != null) append(" (${share}% deep)")
        append(". ${today.deepSessions.size} deep-work session${if (today.deepSessions.size == 1) "" else "s"}")
        today.deepSessions.maxOfOrNull { it.seconds }?.let { append(", longest ${dur(it)}") }
        append(".")
        if (share != null && prev != null) append(" " + when { share > prev + 2 -> "Better than yesterday."; share < prev - 2 -> "Not as good as yesterday."; else -> "About the same as yesterday." })
        if (rating != null) append("\nHow the day felt: ${feeling(rating).substringAfter(' ')}.")
        if (!helped.isNullOrBlank()) append("\nWhat helped: ${helped.trim()}")
        if (!hurt.isNullOrBlank()) append("\nWhat hurt: ${hurt.trim()}")
        if (!change.isNullOrBlank()) append("\nTomorrow I'll: ${change.trim()}")
        if (struggles.isNotEmpty()) append("\nAlso today: " + struggles.joinToString("; "))
        append("\nNo need to reply — but a 👍 helps.")
    }
}
