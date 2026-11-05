package dev.ontask.android.track

import android.content.Context
import dev.ontask.Category
import dev.ontask.Level
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A stretch of phone use: one app (and site, page or video), one category, in or out of work hours. [subject] is what
 * "Where the time went" shows (the site, or the app's name); [item] is set for a page or video on a Varies site.
 */
@Serializable
data class Seg(
    val start: Long, var end: Long, val pkg: String, val app: String, val subject: String,
    val title: String? = null, val item: String? = null, var cat: String, var level: String, val work: Boolean,
)

/** The day's numbers for the heat map (all additive across devices). */
@Serializable
data class Parts(var deep: Long = 0, var active: Long = 0, var workDeep: Long = 0, var workActive: Long = 0, var offWasted: Long = 0, var offActive: Long = 0)

/**
 * Today's phone use, kept in a small file per day (raw activity never leaves the phone; only the day report does),
 * plus a year of per-day heat numbers.
 */
class DayLog(ctx: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val dir = File(ctx.filesDir, "days").apply { mkdirs() }
    private val heatFile = File(ctx.filesDir, "heat.json")
    private val json = Json { ignoreUnknownKeys = true }
    var date: LocalDate = LocalDate.now(zone); private set
    val segs: MutableList<Seg> = load(date)
    val heat: MutableMap<String, Parts> = runCatching { json.decodeFromString<Map<String, Parts>>(heatFile.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun file(d: LocalDate) = File(dir, "$d.json")
    private fun load(d: LocalDate): MutableList<Seg> = runCatching { json.decodeFromString<List<Seg>>(file(d).readText()).toMutableList() }.getOrDefault(mutableListOf())

    val dayStart: Long get() = date.atStartOfDay(zone).toEpochSecond()

    /** Records [sec] seconds ending at [now] of [seg]'s kind: extends the last stretch when it's the same thing. */
    @Synchronized
    fun add(now: Long, sec: Long, pkg: String, app: String, subject: String, title: String?, item: String?, cat: Category, work: Boolean, level: Level = cat.level) {
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        if (today != date) { save(); date = today; segs.clear(); segs.addAll(load(today)); dir.listFiles()?.filter { it.name < "${today.minusDays(14)}.json" }?.forEach { it.delete() } }
        val last = segs.lastOrNull()
        if (last != null && last.pkg == pkg && last.subject == subject && last.item == item && last.cat == cat.name && last.level == level.name && last.work == work && now - sec - last.end <= 10) last.end = now
        else segs.add(Seg(now - sec, now, pkg, app, subject, title, item, cat.name, level.name, work))
        val p = heat.getOrPut(today.toString()) { Parts() }
        p.active += sec
        if (level == Level.DEEP) p.deep += sec
        if (work) { p.workActive += sec; if (level == Level.DEEP) p.workDeep += sec }
        else { p.offActive += sec; if (level == Level.WASTED) p.offWasted += sec }
    }

    /** A page's category decided after the fact: its stretches today change (and the heat numbers are recounted). */
    @Synchronized
    fun recategorize(match: (Seg) -> Boolean, cat: Category) {
        segs.filter(match).forEach { it.cat = cat.name; it.level = cat.level.name }
        val p = Parts()
        segs.forEach { s ->
            val sec = s.end - s.start; val l = Level.valueOf(s.level)
            p.active += sec; if (l == Level.DEEP) p.deep += sec
            if (s.work) { p.workActive += sec; if (l == Level.DEEP) p.workDeep += sec } else { p.offActive += sec; if (l == Level.WASTED) p.offWasted += sec }
        }
        heat[date.toString()] = p
    }

    /** An earlier day's stretches (today's are [snapshot]). */
    @Synchronized fun segsFor(d: LocalDate): List<Seg> = if (d == date) segs.map { it.copy() } else load(d)

    /**
     * A rule changed: today's and the earlier days' stretches it covers (the 14 days kept) take its category, and their
     * heat numbers are recounted. Returns the earlier days that changed (today's go up with the next upload).
     */
    @Synchronized
    fun recategorizeAll(match: (Seg) -> Boolean, cat: Category): Pair<Boolean, List<LocalDate>> {
        val todayChanged = segs.any { match(it) && it.cat != cat.name }
        if (todayChanged) recategorize(match, cat)
        val past = (1L..14L).map { date.minusDays(it) }.filter { d ->
            val day = load(d); val hit = day.filter { match(it) && (it.cat != cat.name || it.level != cat.level.name) }
            if (hit.isEmpty()) return@filter false
            hit.forEach { it.cat = cat.name; it.level = cat.level.name }
            runCatching { file(d).writeText(json.encodeToString(day.toList())) }
            heat[d.toString()] = partsOf(day)
            true
        }
        if (past.isNotEmpty()) runCatching { heatFile.writeText(json.encodeToString(heat.toMap())) }
        return todayChanged to past
    }

    private fun partsOf(day: List<Seg>) = Parts().also { p ->
        day.forEach { s ->
            val sec = s.end - s.start; val l = Level.valueOf(s.level)
            p.active += sec; if (l == Level.DEEP) p.deep += sec
            if (s.work) { p.workActive += sec; if (l == Level.DEEP) p.workDeep += sec } else { p.offActive += sec; if (l == Level.WASTED) p.offWasted += sec }
        }
    }

    @Synchronized
    fun save() {
        runCatching { file(date).writeText(json.encodeToString(segs.toList())) }
        val cutoff = date.minusWeeks(53).toString()
        heat.keys.filter { it < cutoff }.forEach { heat.remove(it) }
        runCatching { heatFile.writeText(json.encodeToString(heat.toMap())) }
    }

    @Synchronized fun snapshot(): List<Seg> = segs.map { it.copy() }
}
