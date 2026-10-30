package dev.ontask.classify

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.Sample
import dev.ontask.Source
import java.net.URI

enum class RuleKind { TITLE, DOMAIN, APP }

data class Rule(
    val id: Long,
    val kind: RuleKind,
    val pattern: String,
    val category: Category,
    val user: Boolean,
)

data class Classification(val category: Category, val source: Source, val ruleId: Long? = null) {
    val level: Level get() = category.level
}

fun domainOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = runCatching { URI(url.trim()).host }.getOrNull() ?: return null
    return host.lowercase().removePrefix("www.").ifEmpty { null }
}

fun isAway(s: Sample, awayAfterSec: Int): Boolean =
    s.locked || s.asleep || (s.idleSec >= awayAfterSec && !s.mediaPlaying)

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
        for (r in ordered) {
            val hit = when (r.kind) {
                RuleKind.TITLE -> title != null && title.contains(r.pattern.lowercase())
                RuleKind.DOMAIN -> domain != null && (domain == r.pattern || domain.endsWith("." + r.pattern))
                RuleKind.APP -> r.pattern.equals(s.app, ignoreCase = true) || r.pattern.equals(s.bundleId, ignoreCase = true)
            }
            if (hit) return Classification(r.category, if (r.user) Source.USER_LABEL else Source.RULE, if (r.user) r.id else null)
        }
        return Classification(Category.UNKNOWN, Source.METADATA)
    }

    companion object {
        val AWAY = Classification(Category.AWAY, Source.AWAY)
    }
}
