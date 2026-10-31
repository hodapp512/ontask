package dev.ontask.block

import dev.ontask.db.Db

data class FocusSession(val id: Long, val startTs: Long, val plannedEndTs: Long, val endedTs: Long?, val completed: Boolean)

/** On-demand Focus session: applies work-hours (strict) rules for a chosen duration; logged as a focus attempt. */
class Focus(private val db: Db, private val events: Events) {
    fun start(minutes: Int, now: Long): FocusSession {
        require(minutes in 5..480) { "Focus sessions last 5–480 minutes" }
        active(now)?.let { return it }
        db.use { c -> c.prepareStatement("INSERT INTO focus_sessions(start_ts,planned_end_ts) VALUES (?,?)").use { it.setLong(1, now); it.setLong(2, now + minutes * 60L); it.executeUpdate() } }
        events.emit("focus_start", now, "minutes" to minutes)
        return active(now)!!
    }

    fun active(now: Long): FocusSession? = list("WHERE ended_ts IS NULL AND planned_end_ts > $now ORDER BY id DESC LIMIT 1").firstOrNull()

    /** Ends the session now; counts as completed when the planned time had elapsed. */
    fun stop(now: Long) {
        val s = active(now) ?: return
        db.use { c -> c.prepareStatement("UPDATE focus_sessions SET ended_ts=?, completed=0 WHERE id=?").use { it.setLong(1, now); it.setLong(2, s.id); it.executeUpdate() } }
        events.emit("focus_end", now, "completed" to false, "minutes" to (now - s.startTs) / 60)
    }

    /** Marks sessions whose time ran out as completed; call periodically. */
    fun settle(now: Long) {
        val done = list("WHERE ended_ts IS NULL AND planned_end_ts <= $now")
        done.forEach { s ->
            db.use { c -> c.prepareStatement("UPDATE focus_sessions SET ended_ts=?, completed=1 WHERE id=?").use { it.setLong(1, s.plannedEndTs); it.setLong(2, s.id); it.executeUpdate() } }
            events.emit("focus_end", now, "completed" to true, "minutes" to (s.plannedEndTs - s.startTs) / 60)
        }
    }

    fun recent(limit: Int = 20) = list("ORDER BY id DESC LIMIT $limit")

    private fun list(tail: String): List<FocusSession> = db.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id,start_ts,planned_end_ts,ended_ts,completed FROM focus_sessions $tail").use { rs ->
                buildList { while (rs.next()) add(FocusSession(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4).takeIf { !rs.wasNull() }, rs.getInt(5) == 1)) }
            }
        }
    }
}
