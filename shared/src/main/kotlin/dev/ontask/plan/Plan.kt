package dev.ontask.plan

/**
 * Today's plan as a list of tasks you check off (shared by the Mac and the phone through users/{uid}/plans/{date}).
 * A finished task stays on the list, struck through. There's no limit, but past [SOFT_MAX] a note suggests saving
 * some for tomorrow.
 */
data class PlanItem(val id: String, val text: String, val done: Boolean = false)

object Plan {
    const val SOFT_MAX = 5
    const val ADD_HINT = "Add your task"
    const val TOO_MANY = "More than 5 is hard to finish — maybe save some for tomorrow."

    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    /**
     * An old free-form plan as tasks: one per line, and a line with several sentences split into one per sentence
     * ("Call Warren. Figure out scheduling." → two tasks). Bullets and numbering are dropped.
     */
    fun split(text: String): List<String> = text.lines()
        .map { it.trim().replace(Regex("^([-*•]|\\d+[.)])\\s+"), "") }
        .filter { it.isNotBlank() }
        .flatMap { line -> line.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'(])")).map { it.trim().removeSuffix(".").trim() } }
        .filter { it.isNotBlank() }

    fun fromText(text: String): List<PlanItem> = split(text).map { PlanItem(newId(), it) }

    /** The next task not done yet (shown where there's room for only one, like the phone's step-1 notification). */
    fun next(items: List<PlanItem>): PlanItem? = items.firstOrNull { !it.done }

    /** "2 of 4 done". */
    fun progress(items: List<PlanItem>): String = "${items.count { it.done }} of ${items.size} done"

    /** Plain text, for texts and push notifications (no strikethrough there): finished tasks get a ✓ in front. */
    fun plainText(items: List<PlanItem>): String = items.joinToString("\n") { (if (it.done) "✓ " else "• ") + it.text }

    fun toMaps(items: List<PlanItem>): List<Map<String, Any>> = items.map { mapOf("id" to it.id, "text" to it.text, "done" to it.done) }

    fun fromMaps(raw: Any?): List<PlanItem> = (raw as? List<*>)?.mapNotNull { m ->
        val o = m as? Map<*, *> ?: return@mapNotNull null
        val text = (o["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        PlanItem((o["id"] as? String) ?: newId(), text, o["done"] == true)
    } ?: emptyList()
}
