package dev.ontask.ai

import dev.ontask.Category
import dev.ontask.db.Db

/**
 * How often Kev or Jev agreed with you. Every AI answer (sure or not) is remembered by what it was about: a page or
 * video, or a site or app. When you later answer the question about it or correct its label, that answer is scored
 * once, as a match or a miss. Labels you never touched aren't counted, so the number isn't flattered.
 */
class AiAccuracy(private val db: Db) {
    /** The AI's answer about [key] (an item key, a site such as "reddit.com", or an app name). Replaces an older one. */
    fun guessed(key: String, provider: AiProvider, category: Category, now: Long) = db.use { c ->
        c.prepareStatement("INSERT INTO ai_guesses(key, provider, category, ts) VALUES (?,?,?,?) ON CONFLICT(key) DO UPDATE SET provider=excluded.provider, category=excluded.category, ts=excluded.ts")
            .use { it.setString(1, key); it.setString(2, provider.name); it.setString(3, category.name); it.setLong(4, now); it.executeUpdate() }
    }

    /** You decided [key] is [category]: scores the AI's answer about it, if there was one not yet scored. */
    fun answered(key: String, category: Category, now: Long) = db.tx { c ->
        val guess = c.prepareStatement("SELECT provider, category FROM ai_guesses WHERE key=?").use { ps ->
            ps.setString(1, key); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        } ?: return@tx
        c.prepareStatement("INSERT INTO ai_checks(ts, provider, matched) VALUES (?,?,?)")
            .use { it.setLong(1, now); it.setString(2, guess.first); it.setInt(3, if (guess.second == category.name) 1 else 0); it.executeUpdate() }
        c.prepareStatement("DELETE FROM ai_guesses WHERE key=?").use { it.setString(1, key); it.executeUpdate() }
    }

    /** One week of one model: [weekStart] is the Monday (epoch seconds, local time). */
    data class Week(val provider: AiProvider, val weekStart: Long, val checked: Int, val matched: Int)

    /** Scored answers per model per week, newest week first, over the last [weeks] weeks. */
    fun weekly(now: Long, weeks: Int = 8): List<Week> {
        val zone = java.time.ZoneId.systemDefault()
        val monday = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate().with(java.time.DayOfWeek.MONDAY)
        val starts = (0 until weeks).map { monday.minusWeeks(it.toLong()).atStartOfDay(zone).toEpochSecond() }
        val rows = db.use { c ->
            c.prepareStatement("SELECT ts, provider, matched FROM ai_checks WHERE ts >= ?").use { ps ->
                ps.setLong(1, starts.last()); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getInt(3))) } }
            }
        }
        return rows.groupBy { (ts, p, _) -> starts.first { ts >= it } to p }
            .mapNotNull { (k, v) -> runCatching { AiProvider.valueOf(k.second) }.getOrNull()?.let { Week(it, k.first, v.size, v.count { it.third == 1 }) } }
            .sortedWith(compareByDescending<Week> { it.weekStart }.thenBy { it.provider })
    }
}
