package dev.ontask.block

import dev.ontask.db.Db
import dev.ontask.db.Prefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class UnblockKind { REGULAR, WORK }

data class Unblock(val id: Long, val ts: Long, val kind: UnblockKind, val domain: String, val purpose: String?, val expiresTs: Long, val endedTs: Long?)

class UnblockRejected(msg: String) : Exception(msg)

/** Regular unblocks (escalating delay, auto-expiring) and purpose-bound work unblocks (no delay, partner notified). */
class Unblocks(private val db: Db, private val prefs: Prefs, private val events: Events, private val zone: ZoneId = ZoneId.systemDefault()) {

    val delays: List<Int> get() = prefs.str("unblock.delays", "10,30,120,300").split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(10, 30, 120, 300) }
    val resetHour: Int get() = prefs.int("unblock.reset_hour", 4)
    val durationsMin: List<Int> = listOf(5, 15, 30)
    val maxWorkMinutes get() = prefs.int("unblock.max_work_min", 180)

    /** Start of the current "unblock day" (resets at 4am). */
    fun dayStart(now: Long): Long {
        val t = Instant.ofEpochSecond(now).atZone(zone)
        var d: LocalDate = t.toLocalDate()
        if (t.hour < resetHour) d = d.minusDays(1)
        return d.atTime(resetHour, 0).atZone(zone).toEpochSecond()
    }

    fun todayCount(now: Long): Int = db.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM unblocks WHERE kind='REGULAR' AND ts >= ?").use { ps ->
            ps.setLong(1, dayStart(now)); ps.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    /** Seconds the user must wait before their next regular unblock: 10s, 30s, 2m, then 5m (configurable). */
    fun nextDelaySec(now: Long): Int = delays[minOf(todayCount(now), delays.size - 1)]

    fun unblocksInLast(now: Long, windowSec: Long): Int = db.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM unblocks WHERE kind='REGULAR' AND ts >= ?").use { ps ->
            ps.setLong(1, now - windowSec); ps.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    /** The caller is responsible for having shown the delay; [delayWaitedSec] is verified against the schedule. */
    fun startRegular(domains: List<String>, minutes: Int, now: Long, delayWaitedSec: Int): List<Unblock> {
        if (minutes !in durationsMin) throw UnblockRejected("Duration must be one of $durationsMin minutes")
        if (domains.isEmpty()) throw UnblockRejected("Pick at least one site")
        val need = nextDelaySec(now)
        if (delayWaitedSec < need) throw UnblockRejected("You need to wait ${need}s before unblocking")
        val out = domains.map { insert(UnblockKind.REGULAR, it, null, now, now + minutes * 60L) }
        events.emit("unblock", now, "domains" to domains.joinToString(","), "minutes" to minutes, "count_today" to todayCount(now))
        return out
    }

    /** No delay; always notifies the partner; reported separately. */
    fun startPurpose(domain: String, purpose: String, minutes: Int, now: Long): Unblock {
        if (purpose.isBlank()) throw UnblockRejected("State what the site is for")
        if (minutes < 1 || minutes > maxWorkMinutes) throw UnblockRejected("Work unblocks can last 1–$maxWorkMinutes minutes")
        val u = insert(UnblockKind.WORK, domain, purpose.trim(), now, now + minutes * 60L)
        events.emit("work_unblock", now, "domain" to domain, "purpose" to purpose.trim(), "minutes" to minutes)
        return u
    }

    private fun insert(kind: UnblockKind, domain: String, purpose: String?, now: Long, expires: Long): Unblock = db.use { c ->
        c.prepareStatement("INSERT INTO unblocks(ts,kind,domain,purpose,expires_ts) VALUES (?,?,?,?,?)").use {
            it.setLong(1, now); it.setString(2, kind.name); it.setString(3, domain); it.setString(4, purpose); it.setLong(5, expires); it.executeUpdate()
        }
        val id = c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
        Unblock(id, now, kind, domain, purpose, expires, null)
    }

    fun active(now: Long): List<Unblock> = query("WHERE ended_ts IS NULL AND expires_ts > ?", now)

    fun recent(limit: Int = 30): List<Unblock> = query("WHERE 1=1 ORDER BY id DESC LIMIT $limit", null)

    private fun query(where: String, arg: Long?): List<Unblock> = db.use { c ->
        c.prepareStatement("SELECT id,ts,kind,domain,purpose,expires_ts,ended_ts FROM unblocks $where").use { ps ->
            if (arg != null) ps.setLong(1, arg)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Unblock(rs.getLong(1), rs.getLong(2), UnblockKind.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7).takeIf { !rs.wasNull() })) }
            }
        }
    }

    fun end(id: Long, now: Long, reason: String = "user") = db.use { c ->
        c.prepareStatement("UPDATE unblocks SET ended_ts=?, end_reason=? WHERE id=? AND ended_ts IS NULL").use { it.setLong(1, now); it.setString(2, reason); it.setLong(3, id); it.executeUpdate() }
    }
}
