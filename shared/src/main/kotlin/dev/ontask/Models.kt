package dev.ontask

import kotlinx.serialization.Serializable

/** [MIXED] is shown as "Neutral" (renamed Nov 3, 2026; the stored name stays). */
enum class Level(val label: String) { DEEP("Focused"), MIXED("Neutral"), WASTED("Wasted"), AWAY("Away") }

/**
 * A category: built-in (named after the old fixed list, e.g. CORE_WORK) or made by the user (`c_<n>`). Its [level]
 * decides focused / mixed / wasted. A [variable] category (e.g. YouTube) is decided per page or video instead.
 * The live list is in [registry], loaded from the database by CategoryStore; all built-ins can be renamed or deleted.
 */
class Category(val name: String, val label: String, val level: Level, val variable: Boolean = false) {
    override fun equals(other: Any?) = other is Category && other.name == name
    override fun hashCode() = name.hashCode()
    override fun toString() = name

    companion object {
        val CORE_WORK = Category("CORE_WORK", "Work", Level.DEEP)
        val WORK_SUPPORT = Category("WORK_SUPPORT", "Work adjacent", Level.DEEP)
        val ENTERTAINMENT = Category("ENTERTAINMENT", "Entertainment / News / Social", Level.WASTED)
        val PERSONAL_ADMIN = Category("PERSONAL_ADMIN", "Personal admin", Level.MIXED)
        val VARIABLE = Category("VARIABLE", "Varies", Level.MIXED, variable = true)
        /**
         * Moments between things (a new tab, a notification alert, Spotlight…): counts as what's around it for up to 2
         * minutes, then as Neutral (see [Borrow]). Never asked about.
         */
        val OTHER = Category("OTHER", "Other", Level.MIXED)
        val UNKNOWN = Category("UNKNOWN", "Unclassified", Level.MIXED)
        val AWAY = Category("AWAY", "Away", Level.AWAY)

        val builtIns = listOf(CORE_WORK, WORK_SUPPORT, VARIABLE, ENTERTAINMENT, PERSONAL_ADMIN, OTHER)

        /** Built-ins of earlier versions; their history is sorted again into the current ones once (CategoryStore.migrate). */
        val retired = listOf("LEARNING", "COMMUNICATION", "SOCIAL", "NEWS", "SHOPPING")
        /** Earlier default labels, replaced by the new ones where you hadn't renamed them yourself. */
        val oldLabels = mapOf("CORE_WORK" to "Core work", "WORK_SUPPORT" to "Work support", "ENTERTAINMENT" to "Entertainment", "VARIABLE" to "Varies (work or not)")

        /** Current categories by name, including UNKNOWN and AWAY. Replaced as a whole when the user edits them. */
        @Volatile var registry: Map<String, Category> = (builtIns + UNKNOWN + AWAY).associateBy { it.name }

        /** Looks a stored name up; deleted or unknown names read as Unclassified. */
        fun valueOf(name: String): Category = registry[name] ?: UNKNOWN

        val entries: List<Category> get() = registry.values.toList()

        /** What feeds and home pages of Varies sites count as: Entertainment, or the first time-wasting category left. */
        val feed: Category get() = registry["ENTERTAINMENT"] ?: registry.values.firstOrNull { it.level == Level.WASTED && !it.variable } ?: UNKNOWN

        /** Categories a user can pick when labelling. */
        val assignable: List<Category> get() = registry.values.filter { it != AWAY && it != UNKNOWN }
    }
}

/** Where a classification came from (design: rule / metadata / OCR / vision / user label). */
enum class Source(val label: String) {
    RULE("Built-in list"), USER_LABEL("Your rule"), METADATA("App & window title"), OCR("AI read the screen"), VISION("AI looked at the screen"), AI_PAGE("AI judged the page"), AWAY("Away")
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
    /** A microphone or camera is in use (a call or meeting): at the computer, even without touching it. */
    val callActive: Boolean = false,
    val accessibilityTrusted: Boolean = false,
    val browserError: String? = null,
)
