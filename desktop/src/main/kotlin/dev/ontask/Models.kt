package dev.ontask

import kotlinx.serialization.Serializable

enum class Level(val label: String) { DEEP("Deep focus"), MIXED("Mixed"), WASTED("Wasted"), AWAY("Away") }

enum class Category(val label: String, val level: Level) {
    CORE_WORK("Core work", Level.DEEP),
    WORK_SUPPORT("Work support", Level.MIXED),
    LEARNING("Learning", Level.DEEP),
    COMMUNICATION("Communication", Level.MIXED),
    ENTERTAINMENT("Entertainment", Level.WASTED),
    SOCIAL("Social", Level.WASTED),
    NEWS("News", Level.WASTED),
    SHOPPING("Shopping", Level.WASTED),
    PERSONAL_ADMIN("Personal admin", Level.MIXED),
    UNKNOWN("Unclassified", Level.MIXED),
    AWAY("Away", Level.AWAY);

    /** Categories a user can pick when labelling. */
    companion object {
        val assignable: List<Category> = entries.filter { it != AWAY && it != UNKNOWN }
    }
}

/** Where a classification came from (design: rule / metadata / OCR / vision / user label). */
enum class Source(val label: String) {
    RULE("Built-in rule"), USER_LABEL("User rule"), METADATA("Metadata only"), OCR("AI on screen text"), VISION("AI vision"), AWAY("Away")
}

/** One reading from the Swift helper. */
@Serializable
data class Sample(
    val app: String? = null,
    val bundleId: String? = null,
    val title: String? = null,
    val url: String? = null,
    val idleSec: Double = 0.0,
    val locked: Boolean = false,
    val asleep: Boolean = false,
    val mediaPlaying: Boolean = false,
    val accessibilityTrusted: Boolean = false,
    val browserError: String? = null,
)
