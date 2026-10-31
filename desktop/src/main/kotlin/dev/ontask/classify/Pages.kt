package dev.ontask.classify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Pages of sites in a Varies category: which are feeds, where each comes from, how to show it, and what to look up. */
object Pages {
    private data class Parts(val host: String, val path: String)

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
        return SITE_NAMES[d] ?: SITE_NAMES.entries.firstOrNull { d.endsWith("." + it.key) }?.value ?: d
    }

    /** "YouTube: Learning Java": the site and the page's own title, without the site's name repeated in it. */
    fun label(domain: String?, title: String?, url: String?): String? {
        val site = siteName(domain) ?: return null
        if (isFeed(url)) return "$site: feed"
        val t = title?.trim().orEmpty()
            .replace(Regex("""^\(\d+\)\s*"""), "") // unread counts: "(3) Facebook"
            .replace(Regex("""\s*[-|·–—:]\s*(on )?${Regex.escape(site)}$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+on ${Regex.escape(site)}$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return if (t.isEmpty() || t.equals(site, true) || t.equals(domain, true)) "$site: ${domain ?: site}" else "$site: ${t.take(120)}"
    }
}

/**
 * Facts a page's title doesn't carry, fetched without your browser's cookies: a YouTube or Vimeo video's channel
 * (oEmbed) and other pages' own short description. Sites that need signing in (Facebook, LinkedIn) are skipped.
 */
class PageLookup(private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
    private val cache = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Map<String, String>>) = size > 300
    }

    fun lookup(url: String?): Map<String, String> {
        val u = url?.trim() ?: return emptyMap()
        synchronized(cache) { cache[u]?.let { return it } }
        val host = runCatching { URI(u).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return emptyMap()
        val facts = runCatching {
            when {
                host.endsWith("youtube.com") && ("/watch" in u || "/shorts/" in u) || host == "youtu.be" -> oembed("https://www.youtube.com/oembed?format=json&url=", u)
                host == "vimeo.com" -> oembed("https://vimeo.com/api/oembed.json?url=", u)
                host == "facebook.com" || host.endsWith("linkedin.com") || host.endsWith("instagram.com") -> emptyMap()
                else -> description(u)
            }
        }.getOrDefault(emptyMap())
        synchronized(cache) { cache[u] = facts }
        return facts
    }

    private fun get(url: String): String? {
        val r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) OnTask").header("Accept-Language", "en").GET().build(), HttpResponse.BodyHandlers.ofString())
        return if (r.statusCode() in 200..299) r.body() else null
    }

    private fun oembed(endpoint: String, url: String): Map<String, String> {
        val o = Json.parseToJsonElement(get(endpoint + URLEncoder.encode(url, Charsets.UTF_8)) ?: return emptyMap()).jsonObject
        return listOfNotNull(o["author_name"]?.jsonPrimitive?.contentOrNull?.let { "Channel" to it }).toMap()
    }

    private fun description(url: String): Map<String, String> {
        val html = get(url)?.take(300_000) ?: return emptyMap()
        fun meta(attr: String, name: String) = Regex("""<meta\s+[^>]*$attr=["']$name["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+[^>]*content=["']([^"']*)["'][^>]*$attr=["']$name["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val desc = (meta("property", "og:description") ?: meta("name", "description"))?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        val site = meta("property", "og:site_name")?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(site?.let { "Publication" to it }, desc?.let { "Description" to it.take(300) }).toMap()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
