package dev.ontask

import kotlinx.serialization.Serializable

enum class Level(val label: String) { DEEP("Focused"), MIXED("Mixed"), WASTED("Wasted"), AWAY("Away") }

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
        val CORE_WORK = Category("CORE_WORK", "Core work", Level.DEEP)
        val WORK_SUPPORT = Category("WORK_SUPPORT", "Work support", Level.MIXED)
        val LEARNING = Category("LEARNING", "Learning", Level.DEEP)
        val COMMUNICATION = Category("COMMUNICATION", "Communication", Level.MIXED)
        val ENTERTAINMENT = Category("ENTERTAINMENT", "Entertainment", Level.WASTED)
        val SOCIAL = Category("SOCIAL", "Social", Level.WASTED)
        val NEWS = Category("NEWS", "News", Level.WASTED)
        val SHOPPING = Category("SHOPPING", "Shopping", Level.WASTED)
        val PERSONAL_ADMIN = Category("PERSONAL_ADMIN", "Personal admin", Level.MIXED)
        val VARIABLE = Category("VARIABLE", "Varies (work or not)", Level.MIXED, variable = true)
        val UNKNOWN = Category("UNKNOWN", "Unclassified", Level.MIXED)
        val AWAY = Category("AWAY", "Away", Level.AWAY)

        val builtIns = listOf(CORE_WORK, WORK_SUPPORT, LEARNING, COMMUNICATION, VARIABLE, ENTERTAINMENT, SOCIAL, NEWS, SHOPPING, PERSONAL_ADMIN)

        /** Current categories by name, including UNKNOWN and AWAY. Replaced as a whole when the user edits them. */
        @Volatile var registry: Map<String, Category> = (builtIns + UNKNOWN + AWAY).associateBy { it.name }

        /** Looks a stored name up; deleted or unknown names read as Unclassified. */
        fun valueOf(name: String): Category = registry[name] ?: UNKNOWN

        val entries: List<Category> get() = registry.values.toList()

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
    val accessibilityTrusted: Boolean = false,
    val browserError: String? = null,
)
