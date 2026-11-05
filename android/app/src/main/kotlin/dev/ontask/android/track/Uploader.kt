package dev.ontask.android.track

import android.os.Build
import dev.ontask.Category
import dev.ontask.android.Cloud

/**
 * The phone's own day report and heat numbers, in the same shapes as the Mac's: users/{uid}/deviceReports,
 * deviceReportItems (titles only you can read) and deviceHeat. The server merges them with the Mac's.
 */
object Uploader {
    private const val MERGE_GAP = 15L
    private const val DEEP_MIN = 25 * 60L

    fun upload(log: DayLog, deviceId: String) = uploadDay(log, deviceId, log.snapshot(), log.dayStart, log.date.toString())

    /** An earlier day the phone still keeps (re-sorted after a category change): its report goes up again. */
    fun uploadPast(log: DayLog, deviceId: String, date: java.time.LocalDate) =
        uploadDay(log, deviceId, log.segsFor(date), date.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond(), date.toString())

    private fun uploadDay(log: DayLog, deviceId: String, segs: List<Seg>, dayStart: Long, date: String) {
        val uid = Cloud.uid ?: return
        val device = mapOf("id" to deviceId, "kind" to "phone", "name" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        val now = System.currentTimeMillis()
        Cloud.db.document("users/$uid/deviceReports/${date}_$deviceId").set(report(segs, dayStart, date) + mapOf("device" to device, "updatedAt" to now))
        Cloud.db.document("users/$uid/deviceReportItems/${date}_$deviceId").set(mapOf("date" to date, "device" to device, "topSites" to sites(segs, titles = true), "refs" to refs(segs), "updatedAt" to now))
        Cloud.db.document("users/$uid/deviceHeat/$deviceId").set(mapOf(
            "device" to device, "updatedAt" to now,
            "days" to log.heat.mapValues { (_, p) -> mapOf("deep" to p.deep, "active" to p.active, "workDeep" to p.workDeep, "workActive" to p.workActive, "offWasted" to p.offWasted, "offActive" to p.offActive) },
        ))
    }

    fun report(segs: List<Seg>, dayStart: Long, date: String): Map<String, Any?> {
        val totals = HashMap<String, Long>(); val work = HashMap<String, Long>()
        segs.forEach { s -> val sec = s.end - s.start; totals.merge(s.level, sec, Long::plus); if (s.work) work.merge(s.level, sec, Long::plus) }
        val runs = runs(segs)
        return mapOf(
            "date" to date, "dayStart" to dayStart, "tz" to java.time.ZoneId.systemDefault().id,
            "totals" to totals, "workTotals" to work,
            "timeline" to runs.map { r ->
                mapOf("a" to r.start - dayStart, "b" to r.end - dayStart, "k" to r.level.first().toString(), "c" to label(r.cat),
                    "t" to r.top.entries.sortedByDescending { it.value }.take(3).map { mapOf("n" to it.key, "s" to it.value) })
            },
            "deepSessions" to deepSessions(runs).map { mapOf("a" to it.first - dayStart, "b" to it.second - dayStart) },
            "distractionEvents" to runs.count { it.level == "WASTED" && it.end - it.start >= 30 },
            "topSites" to sites(segs, titles = false),
        )
    }

    /** What each timeline name refers to (the app or site, or the page or video), so any device can reclassify it. */
    private fun refs(segs: List<Seg>): Map<String, Map<String, Any?>> = segs.associate { s ->
        val name = s.title?.takeIf { s.item != null }?.let { "${s.subject} – $it" } ?: s.subject
        // pkg: the app's package, so a category picked on the Mac makes the same rule the phone uses.
        name to buildMap { put("subject", s.subject); put("pkg", s.pkg); s.item?.let { put("itemKey", it) }; s.title?.let { put("title", it) }; put("categoryId", Category.valueOf(s.cat).name) }
    }

    private class Run(val start: Long, var end: Long, val level: String, val cat: String, val top: HashMap<String, Long>)

    private fun runs(segs: List<Seg>): List<Run> {
        val out = ArrayList<Run>()
        for (s in segs.sortedBy { it.start }) {
            val last = out.lastOrNull()
            val name = s.title?.takeIf { s.item != null }?.let { "${s.subject} – $it" } ?: s.subject
            if (last != null && last.level == s.level && last.cat == s.cat && s.start - last.end <= MERGE_GAP) { last.end = maxOf(last.end, s.end); last.top.merge(name, s.end - s.start, Long::plus) }
            else out.add(Run(s.start, s.end, s.level, s.cat, hashMapOf(name to s.end - s.start)))
        }
        return out
    }

    /** Uninterrupted focused stretches (no time-wasting, gaps under a minute) of 25+ minutes. */
    private fun deepSessions(runs: List<Run>): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>(); var a = -1L; var b = -1L
        for (r in runs) {
            val ok = r.level == "DEEP" || r.level == "MIXED"
            if (ok && a >= 0 && r.start - b <= 60) b = r.end
            else { if (a >= 0 && b - a >= DEEP_MIN) out.add(a to b); if (ok) { a = r.start; b = r.end } else a = -1 }
        }
        if (a >= 0 && b - a >= DEEP_MIN) out.add(a to b)
        return out
    }

    /** "Where the time went": sites and apps (pages and videos by title only in the private copy). */
    private fun sites(segs: List<Seg>, titles: Boolean): List<Map<String, Any?>> {
        data class K(val name: String, val cat: String, val item: String?, val title: String?)
        val sums = LinkedHashMap<K, Long>()
        val pkgs = segs.associate { it.subject to it.pkg }
        segs.forEach { s ->
            val k = if (titles && s.item != null) K("${s.subject}: ${s.title ?: s.item}", s.cat, s.item, s.title) else K(s.subject, s.cat, null, null)
            sums.merge(k, s.end - s.start, Long::plus)
        }
        return sums.entries.filter { it.value >= 10 }.sortedByDescending { it.value }.take(20).map { (k, sec) ->
            buildMap {
                put("name", k.name); put("seconds", sec); put("categoryId", Category.valueOf(k.cat).name)
                put("category", Category.valueOf(k.cat).let { if (it.variable && k.item != null) "Unsure" else it.label })
                k.item?.let { put("itemKey", it) }; k.title?.let { put("title", it) }
                if (k.item == null) pkgs[k.name]?.let { put("pkg", it) }
            }
        }
    }

    private fun label(cat: String): String = Category.valueOf(cat).let { if (it == Category.UNKNOWN) "Unclassified" else if (it.variable) "Unsure" else it.label }
}
