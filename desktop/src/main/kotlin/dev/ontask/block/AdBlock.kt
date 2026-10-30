package dev.ontask.block

import dev.ontask.db.Prefs
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Ad, malware (and optionally adult/fake-news) blocking from a public hosts list on GitHub. Applied at all times,
 * not only during work hours. The list is re-downloaded every [refreshHours]; the last good copy is cached on disk
 * so blocking keeps working offline.
 */
class AdBlock(private val dir: Path, private val prefs: Prefs) {
    companion object {
        const val DEFAULT_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-porn/hosts"
        val PRESETS = listOf(
            "Ads + malware" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "Ads + malware + fake news + adult" to DEFAULT_URL,
            "Ads + malware + fake news + adult + gambling + social" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-gambling-porn-social/hosts",
        )
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")
        private val LINE = Regex("^(0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+([A-Za-z0-9._-]+)")

        /** Keeps only well-formed blocking lines, normalised to `0.0.0.0 host`. Anything else (redirects, comments) is dropped. */
        fun sanitize(raw: String): List<String> = raw.lineSequence()
            .mapNotNull { LINE.find(it.trim())?.groupValues?.get(2)?.lowercase() }
            .filter { it !in SKIP && '.' in it }
            .distinct()
            .map { "0.0.0.0 $it" }
            .toList()
    }

    private val cacheFile: Path get() = dir.resolve("sources").resolve("adblock-hosts")
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    var enabled: Boolean
        get() = prefs.bool("adblock.enabled", true)
        set(v) { prefs.set("adblock.enabled", v); version++ }
    var url: String
        get() = prefs.str("adblock.url", DEFAULT_URL)
        set(v) { prefs.set("adblock.url", v.trim()); prefs.set("adblock.last_refresh", null) }
    var refreshHours: Int
        get() = prefs.int("adblock.refresh_hours", 24)
        set(v) = prefs.set("adblock.refresh_hours", v)
    val lastRefresh: Long get() = prefs.long("adblock.last_refresh", 0)
    @Volatile var lastError: String? = null; private set

    /** Bumped whenever the hosts section changes so callers can skip rebuilding the (large) hosts file. */
    @Volatile var version = 0; private set
    @Volatile private var lines: List<String>? = null

    /** The blocking lines to include right now (empty when disabled). Loaded lazily from the cache. */
    fun section(): List<String> {
        if (!enabled) return emptyList()
        lines?.let { return it }
        val cached = runCatching { if (Files.exists(cacheFile)) sanitize(Files.readString(cacheFile)) else emptyList() }.getOrDefault(emptyList())
        lines = cached
        return cached
    }

    val count: Int get() = lines?.size ?: section().size

    fun refreshDue(now: Long) = enabled && now - lastRefresh >= refreshHours * 3600L

    /** Downloads the list; on failure keeps the cached copy. Returns true if the content changed. */
    fun refresh(now: Long): Boolean {
        return try {
            val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) error("HTTP ${res.statusCode()} from $url")
            val clean = sanitize(res.body())
            if (clean.size < 100) error("The list at $url has only ${clean.size} entries; keeping the previous copy.")
            Files.createDirectories(cacheFile.parent)
            val tmp = Files.createTempFile(cacheFile.parent, "adblock", ".tmp")
            Files.writeString(tmp, clean.joinToString("\n"))
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            prefs.set("adblock.last_refresh", now.toString())
            lastError = null
            val changed = clean != lines
            lines = clean
            if (changed) version++
            changed
        } catch (e: Exception) {
            lastError = e.message
            // Retry in an hour rather than every tick.
            prefs.set("adblock.last_refresh", (now - refreshHours * 3600L + 3600).toString())
            false
        }
    }
}
