package dev.ontask.block

import dev.ontask.db.Db
import java.nio.file.Files
import java.nio.file.Path

fun normalizeDomain(raw: String): String? {
    var s = raw.trim().lowercase()
    if (s.isEmpty() || s.startsWith("#")) return null
    s = s.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore('?').removePrefix("www.")
    return s.takeIf { Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(it) }
}

class Blocklist(private val db: Db) {
    fun all(): List<String> = db.use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT domain FROM blocklist ORDER BY domain").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    /** Adds domains (invalid lines are skipped); returns how many were new. */
    fun add(domains: Collection<String>, now: Long): Int = db.tx { c ->
        var n = 0
        c.prepareStatement("INSERT OR IGNORE INTO blocklist(domain,added_ts) VALUES (?,?)").use { ps ->
            domains.mapNotNull(::normalizeDomain).distinct().forEach { ps.setString(1, it); ps.setLong(2, now); n += ps.executeUpdate() }
        }
        n
    }

    fun remove(domain: String) = db.use { c -> c.prepareStatement("DELETE FROM blocklist WHERE domain=?").use { it.setString(1, domain); it.executeUpdate() } }

    /** Default list shipped with the app (ported from BlockMe's custom-hosts-default). */
    fun seedDefault(now: Long): Int =
        javaClass.getResourceAsStream("/blocklist-default")?.bufferedReader()?.readLines()?.let { add(it, now) } ?: 0

    /** Reads BlockMe's custom list (read-only). */
    fun importFromBlockMe(path: Path = Path.of(System.getProperty("user.home"), ".blockme", "sources", "custom-hosts"), now: Long): Int =
        if (Files.exists(path)) add(Files.readAllLines(path), now) else 0
}
