package dev.ontask

/**
 * Time in the Other category (a new tab, a notification alert, Spotlight…) counts as what you were on just before,
 * for up to [LIMIT_SEC]; after that, or with nothing just before (within [GAP_SEC]), as Neutral. Feed it every reading.
 */
class Borrow {
    private var lastLevel: Level? = null
    private var lastEnd = 0L
    private var otherSince: Long? = null

    /** The level a reading of [category] at [now] counts as (null: the category's own). */
    fun level(now: Long, category: Category, away: Boolean): Level? {
        if (away) { otherSince = null; return null }
        if (category != Category.OTHER) { otherSince = null; return null }
        val since = otherSince ?: now.also { otherSince = it }
        val prev = lastLevel?.takeIf { since - lastEnd <= GAP_SEC } ?: return null
        return if (now - since < LIMIT_SEC) prev else null
    }

    /** Records what a reading that isn't Other counted as. */
    fun solid(now: Long, level: Level) { lastLevel = level; lastEnd = now }

    companion object {
        const val LIMIT_SEC = 120L
        const val GAP_SEC = 60L
    }
}
