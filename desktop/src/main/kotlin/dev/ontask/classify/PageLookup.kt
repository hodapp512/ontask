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
