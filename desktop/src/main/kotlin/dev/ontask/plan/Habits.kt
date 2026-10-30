package dev.ontask.plan

import dev.ontask.db.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User-defined, clock-scheduled prompts (e.g. morning prayer). Delivered to the phone by the server in phase 4. */
@Serializable
data class Habit(val id: String, val name: String, val time: String, val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7), val enabled: Boolean = true)

class Habits(private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true }
    fun all(): List<Habit> = prefs.get("habits")?.let { runCatching { json.decodeFromString<List<Habit>>(it) }.getOrNull() } ?: emptyList()
    fun save(list: List<Habit>) = prefs.set("habits", json.encodeToString(list))
    fun add(name: String, time: String) = save(all() + Habit(java.util.UUID.randomUUID().toString().take(8), name.trim(), time))
    fun remove(id: String) = save(all().filterNot { it.id == id })
}
