package dev.ontask.classify

import java.net.URI

/** Pages of sites in a Varies category: which are feeds, where each comes from, how to show it, and what to look up. */
object Pages {
    private data class Parts(val host: String, val path: String)

    private val SEARCH_PATHS = mapOf("google.com" to "/search", "bing.com" to "/search", "search.brave.com" to "/search", "duckduckgo.com" to "", "search.yahoo.com" to "/search")

    /**
     * What you searched for on a search engine's results page, or null. A search can be work or not, so each one is
     * judged on its own like a video (whatever site, or rule, the search engine itself has).
     */
    fun searchQuery(url: String?): String? = runCatching {
        val u = URI(url!!.trim())
        val host = u.host!!.lowercase().removePrefix("www.")
        val path = (u.path ?: "").trimEnd('/')
        val want = SEARCH_PATHS[host] ?: return null
        if (path != want && !(want == "/search" && path.startsWith("/search"))) return null
        val q = u.rawQuery?.split('&')?.firstOrNull { it.startsWith("q=") || it.startsWith("p=") }?.substringAfter('=') ?: return null
        java.net.URLDecoder.decode(q, "UTF-8").trim().lowercase().replace(Regex("\\s+"), " ").ifEmpty { null }
    }.getOrNull()

    fun isSearch(url: String?): Boolean = searchQuery(url) != null

    private fun parts(url: String?): Parts? = runCatching {
        val u = URI(url!!.trim())
        Parts(u.host!!.lowercase().removePrefix("www.").removePrefix("m."), (u.path ?: "").trimEnd('/').lowercase())
    }.getOrNull()

    /**
     * Feeds and home pages count as time-wasting without asking anyone: endless scrolling is the time sink whatever
     * it shows. Groups, marketplace, a subreddit, a thread, a video or an article are judged on their own.
     */
    fun isFeed(url: String?): Boolean {
        val p = parts(url) ?: return false
        val path = p.path
        return when {
            p.host.endsWith("youtube.com") -> path == "" || path == "/feed/trending" || path == "/feed/subscriptions" || path.startsWith("/shorts")
            p.host == "facebook.com" -> path == "" || path == "/home.php" || path.startsWith("/watch") || path.startsWith("/reel")
            p.host.endsWith("reddit.com") -> path == "" || path == "/r/popular" || path == "/r/all" || path == "/best" || path == "/hot" || path == "/new"
            p.host == "vimeo.com" -> path == "" || path == "/watch"
            p.host.endsWith("linkedin.com") -> path == "" || path == "/feed"
            else -> false
        }
    }

    /**
     * Where a page comes from, for learning from your answers: a subreddit, Facebook group, Medium author, Substack
     * publication, or (when known) a YouTube channel. Null when there's no meaningful source.
     */
    fun sourceKey(url: String?, channel: String? = null): String? {
        val p = parts(url) ?: return null
        val seg = p.path.split('/').filter { it.isNotEmpty() }
        return when {
            p.host.endsWith("youtube.com") || p.host == "youtu.be" -> channel?.let { "youtube channel ${it.trim()}" }
                ?: seg.firstOrNull()?.takeIf { it.startsWith("@") }?.let { "youtube channel $it" }
            p.host.endsWith("reddit.com") -> if (seg.getOrNull(0) == "r" && seg.size > 1) "reddit r/${seg[1]}" else null
            p.host == "facebook.com" -> if (seg.getOrNull(0) == "groups" && seg.size > 1) "facebook group ${seg[1]}" else null
            p.host == "medium.com" -> seg.firstOrNull()?.takeIf { it.startsWith("@") }?.let { "medium $it" }
            p.host.endsWith(".medium.com") -> "medium ${p.host.removeSuffix(".medium.com")}"
            p.host.endsWith(".substack.com") -> "substack ${p.host.removeSuffix(".substack.com")}"
            p.host == "vimeo.com" -> channel?.let { "vimeo ${it.trim()}" }
            else -> null
        }
    }

    private val SITE_NAMES = mapOf(
        "youtube.com" to "YouTube", "youtu.be" to "YouTube", "reddit.com" to "Reddit", "facebook.com" to "Facebook", "linkedin.com" to "LinkedIn",
        "vimeo.com" to "Vimeo", "medium.com" to "Medium", "substack.com" to "Substack", "instagram.com" to "Instagram", "x.com" to "X",
    )

    fun siteName(domain: String?): String? {
        val d = domain?.removePrefix("www.") ?: return null
        return SITE_NAMES[d] ?: SITE_NAMES.entries.firstOrNull { d.endsWith("." + it.key) }?.value
            ?: siteDomain(d).substringBefore('.').replaceFirstChar { it.uppercase() } // en.wikipedia.org → Wikipedia
    }

    /** "YouTube: Learning Java": the site and the page's own title, without the site's name repeated in it. */
    fun label(domain: String?, title: String?, url: String?): String? {
        val site = siteName(domain) ?: return null
        if (isFeed(url)) return "$site: feed"
        searchQuery(url)?.let { return "$site search: ${it.take(120)}" }
        val t = title?.trim().orEmpty()
            .replace(Regex("""^\(\d+\)\s*"""), "") // unread counts: "(3) Facebook"
            .replace(Regex("""\s*[-|·–—:]\s*(on )?${Regex.escape(site)}$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+on ${Regex.escape(site)}$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return if (t.isEmpty() || t.equals(site, true) || t.equals(domain, true)) "$site: ${domain ?: site}" else "$site: ${t.take(120)}"
    }
}

/**
 * Identifies one page or video: for YouTube the video id, otherwise the address without query or fragment, or the
 * app and window title when there's no address.
 */
fun itemKey(app: String?, title: String?, url: String?): String {
    if (!url.isNullOrBlank()) runCatching {
        val u = URI(url.trim())
        val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return@runCatching
        val v = u.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        if (host.endsWith("youtube.com") && v != null) return "youtube.com/watch?v=$v"
        if (host == "youtu.be") return "youtube.com/watch?v=" + u.path.trim('/')
        Pages.searchQuery(url)?.let { return host + "/search?q=" + it }
        return host + (u.path?.trimEnd('/') ?: "")
    }
    return "${app ?: "?"}|${title ?: ""}"
}
