package dev.ontask.classify

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Sample
import dev.ontask.Source
import java.net.URI

enum class RuleKind { TITLE, DOMAIN, APP }

/**
 * A TITLE rule's [Rule.pattern] is a keyword anywhere in the window title, or "App|Title" for one app's windows with
 * that title: the whole title, its start ("New Tab - Google Chrome"), or the site its title names ([windowSite]).
 */
data class Rule(
    val id: Long,
    val kind: RuleKind,
    val pattern: String,
    val category: Category,
    val user: Boolean,
)

/** [level] differs from the category's only for a Variable category with a verdict for this page or video. */
data class Classification(val category: Category, val source: Source, val ruleId: Long? = null, val level: Level = category.level)

/**
 * The site a rule should cover: the address without language, mobile or www prefixes, so "en.m.wikipedia.org" and
 * "de.wikipedia.org" both mean wikipedia.org. Meaningful subdomains stay ("mail.google.com").
 */
fun siteDomain(host: String): String {
    val labels = host.lowercase().removePrefix("www.").split('.').toMutableList()
    val prefix = Regex("^(www|m|mobile|[a-z]{2}|[a-z]{2}-[a-z]{2,4})$")
    while (labels.size > 2 && prefix.matches(labels.first())) labels.removeAt(0)
    return labels.joinToString(".")
}

/** Browsers on the Mac, by app name. */
val BROWSERS = listOf("Google Chrome", "Safari", "Arc", "Brave Browser", "Microsoft Edge", "Firefox", "Chromium", "Opera", "Vivaldi")

/**
 * The site a browser window's title names, for windows whose address can't be read (pop-ups, installed web apps,
 * mini players): "Inbox (557) - me@gmail.com - Gmail - Google Chrome - Dennis" → "Gmail".
 */
fun windowSite(app: String?, title: String?): String? {
    var t = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    app?.let { a -> t.indexOf(" - $a").takeIf { it > 0 }?.let { t = t.substring(0, it) } }
    return t.split(" - ", " | ", " — ", " – ").last().trim().ifEmpty { null }
}

/** "Google Chrome – Gmail": what a browser window with no address is called, in rows and rules. */
fun browserWindowSubject(app: String?, title: String?, url: String?): String? =
    if (app in BROWSERS && domainOf(url) == null) windowSite(app, title)?.let { "$app – $it" } else null

/** The TITLE rule pattern for a [browserWindowSubject] ("Google Chrome|Gmail"), or null for anything else. */
fun browserWindowPattern(subject: String): String? =
    BROWSERS.firstOrNull { subject.startsWith("$it – ") }?.let { "$it|" + subject.removePrefix("$it – ") }

fun domainOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return null
    return host.lowercase().removePrefix("www.").ifEmpty { null }
}

fun isAway(s: Sample, awayAfterSec: Int): Boolean =
    s.locked || s.asleep || (s.idleSec >= awayAfterSec && !s.mediaPlaying && !s.callActive)

/**
 * Rules are evaluated user-first, then built-in; within each, title keyword beats domain beats app
 * (most specific first), longer patterns beat shorter, newer beats older.
 */
class Classifier(userRules: List<Rule>, builtIn: List<Rule> = DefaultRules.all) {
    private val ordered: List<Rule> = (userRules + builtIn).sortedWith(
        compareByDescending<Rule> { it.user }
            .thenBy { it.kind.ordinal }
            .thenByDescending { it.pattern.length }
            .thenByDescending { it.id },
    )

    fun classify(s: Sample): Classification {
        val domain = domainOf(s.url)
        val title = s.title?.lowercase()
        // A search results page is judged search by search, whatever the search engine's own rule says.
        if (Pages.isSearch(s.url)) Category.registry[Category.VARIABLE.name]?.let { return Classification(it, Source.RULE) }
        for (r in ordered) {
            val hit = when (r.kind) {
                RuleKind.TITLE -> title != null && titleMatches(r.pattern, s.app, s.title!!)
                RuleKind.DOMAIN -> domain != null && (domain == r.pattern || domain.endsWith("." + r.pattern))
                RuleKind.APP -> r.pattern.equals(s.app, ignoreCase = true) || r.pattern.equals(s.bundleId, ignoreCase = true)
            }
            // Rules follow the current categories: renamed or re-levelled ones apply as edited, deleted ones are skipped.
            if (hit) return Classification(Category.registry[r.category.name] ?: continue, if (r.user) Source.USER_LABEL else Source.RULE, if (r.user) r.id else null)
        }
        return Classification(Category.UNKNOWN, Source.METADATA)
    }

    companion object {
        val AWAY = Classification(Category.AWAY, Source.AWAY)

        /** Whether a TITLE rule's [pattern] (a keyword, or "App|Title") matches this window. */
        fun titleMatches(pattern: String, app: String?, title: String): Boolean {
            val bar = pattern.indexOf('|')
            if (bar < 0) return title.contains(pattern, ignoreCase = true)
            if (!pattern.substring(0, bar).equals(app, ignoreCase = true)) return false
            val t = pattern.substring(bar + 1)
            return title.equals(t, true) || title.startsWith("$t - ", true) || windowSite(app, title).equals(t, true)
        }
    }
}
