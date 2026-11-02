package dev.ontask.report

import dev.ontask.Category
import dev.ontask.Level
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * This Mac's day plus the other devices' (the phone's) uploaded day reports, for Home: totals add up, the phone's
 * stretches join the timeline (marked [Run.phone]), its focused sessions and sites join the lists. Each `other` is a
 * device's shared report and, if there is one, its private copy with page and video titles.
 */
fun withDevices(r: DailyReport, others: List<Pair<JsonObject, JsonObject?>>): DailyReport {
    if (others.isEmpty()) return r
    val all = HashMap(r.totalsAll); val work = HashMap(r.totalsWork)
    val runs = ArrayList(r.timeline); val deep = ArrayList(r.deepSessions); val subjects = ArrayList(r.topSubjects)
    var distractions = r.distractionEvents
    fun num(o: JsonObject?, k: String) = o?.get(k)?.jsonPrimitive?.longOrNull ?: 0
    for ((rep, items) in others) {
        (rep["totals"] as? JsonObject)?.forEach { (k, v) -> runCatching { Level.valueOf(k) }.getOrNull()?.let { all.merge(it, v.jsonPrimitive.longOrNull ?: 0, Long::plus) } }
        (rep["workTotals"] as? JsonObject)?.forEach { (k, v) -> runCatching { Level.valueOf(k) }.getOrNull()?.let { work.merge(it, v.jsonPrimitive.longOrNull ?: 0, Long::plus) } }
        (rep["timeline"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            val level = Level.entries.firstOrNull { it.name.first().toString() == o["k"]?.jsonPrimitive?.contentOrNull } ?: return@forEach
            val label = o["c"]?.jsonPrimitive?.contentOrNull
            val top = (o["t"] as? JsonArray)?.associate { t -> t.jsonObject["n"]!!.jsonPrimitive.content to num(t.jsonObject, "s") } ?: emptyMap()
            runs.add(Run(r.dayStart + num(o, "a"), r.dayStart + num(o, "b"), level, top, Category.registry.values.firstOrNull { it.label == label }, phone = true))
        }
        (rep["deepSessions"] as? JsonArray)?.forEach { e -> deep.add(DeepSession(r.dayStart + num(e.jsonObject, "a"), r.dayStart + num(e.jsonObject, "b"))) }
        distractions += num(rep, "distractionEvents").toInt()
        ((items ?: rep)["topSites"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            subjects.add(Subject(o["name"]?.jsonPrimitive?.contentOrNull ?: "?", Category.valueOf(o["categoryId"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"),
                num(o, "seconds"), o["itemKey"]?.jsonPrimitive?.contentOrNull, o["title"]?.jsonPrimitive?.contentOrNull, phone = true))
        }
    }
    return r.copy(
        totalsAll = all, totalsWork = work, timeline = runs.sortedBy { it.start }, deepSessions = deep.sortedBy { it.start },
        distractionEvents = distractions, topSubjects = subjects.sortedByDescending { it.seconds }.take(20),
    )
}
