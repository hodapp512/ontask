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
    val otherSubjects = ArrayList<Subject>()
    var distractions = r.distractionEvents
    fun num(o: JsonObject?, k: String) = o?.get(k)?.jsonPrimitive?.longOrNull ?: 0
    for ((rep, items) in others) {
        // What each of the phone's names refers to, so its rows and stretches can be reclassified here.
        val refs = (items?.get("refs") as? JsonObject)?.mapValues { (_, v) ->
            val o = v.jsonObject
            ItemRef(o["subject"]?.jsonPrimitive?.contentOrNull ?: "", o["itemKey"]?.jsonPrimitive?.contentOrNull, o["title"]?.jsonPrimitive?.contentOrNull)
        } ?: emptyMap()
        (rep["totals"] as? JsonObject)?.forEach { (k, v) -> runCatching { Level.valueOf(k) }.getOrNull()?.let { all.merge(it, v.jsonPrimitive.longOrNull ?: 0, Long::plus) } }
        (rep["workTotals"] as? JsonObject)?.forEach { (k, v) -> runCatching { Level.valueOf(k) }.getOrNull()?.let { work.merge(it, v.jsonPrimitive.longOrNull ?: 0, Long::plus) } }
        (rep["timeline"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            val level = Level.entries.firstOrNull { it.name.first().toString() == o["k"]?.jsonPrimitive?.contentOrNull } ?: return@forEach
            val label = o["c"]?.jsonPrimitive?.contentOrNull
            val top = (o["t"] as? JsonArray)?.associate { t -> t.jsonObject["n"]!!.jsonPrimitive.content to num(t.jsonObject, "s") } ?: emptyMap()
            runs.add(Run(r.dayStart + num(o, "a"), r.dayStart + num(o, "b"), level, top, Category.registry.values.firstOrNull { it.label == label }, refs.filterKeys { it in top }, phone = true))
        }
        (rep["deepSessions"] as? JsonArray)?.forEach { e -> deep.add(DeepSession(r.dayStart + num(e.jsonObject, "a"), r.dayStart + num(e.jsonObject, "b"))) }
        distractions += num(rep, "distractionEvents").toInt()
        ((items ?: rep)["topSites"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            otherSubjects.add(Subject(o["name"]?.jsonPrimitive?.contentOrNull ?: "?", Category.valueOf(o["categoryId"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"),
                num(o, "seconds"), o["itemKey"]?.jsonPrimitive?.contentOrNull, o["title"]?.jsonPrimitive?.contentOrNull, phone = true))
        }
    }
    // Where the phone was in use, the Mac's overlapping time is cut (timeline and totals), as on the server.
    val busy = runs.filter { it.phone && it.level != Level.AWAY }
    val clipped = ArrayList<Run>()
    val removed = HashMap<Level, Long>()
    for (run in runs) {
        if (run.phone) { clipped.add(run); continue }
        val (pieces, cut) = clipToPhone(run, busy)
        clipped.addAll(pieces)
        if (cut > 0 && run.level != Level.AWAY) removed.merge(run.level, cut, Long::plus)
    }
    // "Where the time went" loses the same minutes: from the row of that name, the rest in proportion.
    val cutByName = HashMap<String, Long>()
    for (run in runs) if (!run.phone && run.level != Level.AWAY) {
        val kept = clipped.filter { !it.phone && it.start >= run.start && it.end <= run.end }.sumOf { it.end - it.start }
        val len = run.end - run.start
        if (kept < len) run.activity.forEach { (n, sec) -> cutByName.merge(n, sec * (len - kept) / len, Long::plus) }
    }
    var restCut = removed.values.sum()
    val macRows = subjects.filter { !it.phone }.map { s -> val c = minOf(s.seconds, cutByName[s.name] ?: 0); restCut -= c; s to s.seconds - c }
    val macSum = macRows.sumOf { it.second }
    val trimmedMac = macRows.map { (s, sec) -> s.copy(seconds = if (restCut > 0 && macSum > 0) (sec - restCut * sec / macSum).coerceAtLeast(0) else sec) }
    subjects.clear(); subjects.addAll(trimmedMac.filter { it.seconds >= MIN_SUBJECT_SEC } + otherSubjects)
    removed.forEach { (l, cut) ->
        val total = all[l] ?: 0
        if (total > 0) work[l] = ((work[l] ?: 0) - cut * (work[l] ?: 0) / total).coerceAtLeast(0)
        all[l] = (total - cut).coerceAtLeast(0)
    }
    return r.copy(
        totalsAll = all, totalsWork = work, timeline = clipped.sortedBy { it.start }, deepSessions = deep.sortedBy { it.start },
        distractionEvents = distractions, topSubjects = subjects.sortedByDescending { it.seconds }.take(20),
    )
}

/**
 * A Mac stretch without the parts where the phone was in use (the phone counts only while its screen is on and
 * unlocked; the Mac keeps counting for minutes after your last keypress, so then you were on the phone). Returns the
 * remaining pieces, per-app seconds scaled to what's left, and the seconds cut.
 */
fun clipToPhone(run: Run, phone: List<Run>): Pair<List<Run>, Long> {
    var parts = listOf(run.start to run.end)
    for (p in phone) {
        if (p.end <= run.start || p.start >= run.end) continue
        parts = parts.flatMap { (a, b) -> if (p.end <= a || p.start >= b) listOf(a to b) else listOf(a to minOf(b, p.start), maxOf(a, p.end) to b).filter { it.second > it.first } }
    }
    val len = run.end - run.start
    val kept = parts.sumOf { it.second - it.first }
    if (kept == len) return listOf(run) to 0
    return parts.map { (a, b) -> run.copy(start = a, end = b, activity = run.activity.mapValues { it.value * (b - a) / len }.filterValues { it > 0 }) } to (len - kept)
}
