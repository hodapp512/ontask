package dev.ontask.cloud

import dev.ontask.Category
import dev.ontask.Level
import dev.ontask.block.Events
import dev.ontask.db.Prefs
import dev.ontask.db.Settings
import dev.ontask.plan.Checkins
import dev.ontask.plan.Habits
import dev.ontask.report.DailyReport
import dev.ontask.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

data class Reaction(val kind: String, val reply: String?, val ts: Long)

/** Who this Mac is linked to, for the settings screen. Refreshed every few minutes while syncing. */
data class LinkInfo(val name: String?, val email: String?, val phones: List<Pair<String, Long>>, val partners: List<String>, val supporting: List<String>)

/**
 * Pushes what the user chose to share to Firestore and pulls partner reactions. The raw activity log and OCR text
 * never leave the Mac: only events, daily aggregates, the schedule and check-in status.
 */
class CloudSync(
    private val prefs: Prefs,
    private val events: Events,
    private val reports: Reports,
    private val settings: () -> Settings,
    private val habits: Habits,
    private val checkins: Checkins,
    private val clientFactory: (CloudConfig) -> CloudClient = { CloudClient(it, dev.ontask.ai.Keychain()) },
    /** Applies a correction sent from the phone: file this app or site under that category. */
    private val relabel: (name: String, category: String) -> Unit = { _, _ -> },
    /** The same for one page or video on a Varies site: [itemKey] gets that category. */
    private val relabelItem: (itemKey: String, title: String?, category: String) -> Unit = { _, _, _ -> },
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** When you last used the Mac (keyboard, mouse or a video in front of you), epoch seconds; null if unknown. */
    private val lastActive: () -> Long? = { null },
    /** Today's plan, for the daily report. */
    private val plan: (LocalDate) -> List<String> = { emptyList() },
    /** Focused % before and after lunch (or noon), for "Morning 64% focused · Afternoon 48%". */
    private val halves: (LocalDate) -> Pair<Int?, Int?> = { null to null },
    /** The heat map's year: each day's numbers, and the current streak. */
    private val heat: () -> Pair<Map<LocalDate, dev.ontask.report.DayStat>, Int>? = { null },
) {
    @Volatile var lastError: String? = null; private set
    @Volatile var lastSyncTs: Long = 0; private set
    private var client: CloudClient? = null
    private var clientCfg: CloudConfig? = null
    private var lastReportTs = 0L
    private var lastHeatTs = 0L
    private var lastInfoTs = 0L
    private val json = Json

    val configured: Boolean get() = CloudConfig.fromPrefs(prefs) != null

    fun client(): CloudClient? {
        val cfg = CloudConfig.fromPrefs(prefs) ?: return null
        if (client == null || cfg != clientCfg) { client = clientFactory(cfg); clientCfg = cfg }
        return client
    }

    val config: CloudConfig? get() = CloudConfig.fromPrefs(prefs)

    /** Links this Mac with the 5-character code shown on the phone (Me → Link my Mac). */
    fun linkWithCode(code: String) {
        val c = client() ?: throw CloudException("OnTask isn't connected to a server")
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
        val res = try { c.callAnonymous("redeemLinkCode", buildJsonObject { put("code", clean) }) }
            catch (e: CloudException) { throw CloudException(if (e.status == 404) "That code is wrong or has expired. Make a new one on your phone." else e.message ?: "Couldn't link") }
        c.signInWithCustomToken(res["token"]?.jsonPrimitive?.content ?: throw CloudException("Couldn't link"))
        prefs.set("cloud.account_name", res["name"]?.jsonPrimitive?.contentOrNull)
        prefs.set("cloud.account_email", res["email"]?.jsonPrimitive?.contentOrNull)
        prefs.set("cloud.linked_at", (System.currentTimeMillis() / 1000).toString())
        lastInfoTs = 0
    }
    fun linkThisMac(deviceToken: String) { client()?.signInWithCustomToken(deviceToken) ?: throw CloudException("OnTask isn't connected to a server") }
    fun signOut() {
        runCatching { client()?.let { c -> c.ensureUid().let { uid -> c.delete("users/$uid/devices/${deviceId()}") } } }
        client()?.signOut()
        listOf("cloud.account_name", "cloud.account_email", "cloud.link_info", "cloud.linked_at").forEach { prefs.set(it, null) }
    }
    val signedIn: Boolean get() = client()?.signedIn == true

    fun reactions(): List<Reaction> = prefs.get("cloud.reactions")?.let { runCatching { json.parseToJsonElement(it).jsonArray.map { e -> val o = e.jsonObject; Reaction(o["kind"]!!.jsonPrimitive.content, o["reply"]?.jsonPrimitive?.content, o["ts"]!!.jsonPrimitive.longOrNull ?: 0) } }.getOrNull() } ?: emptyList()

    /** One sync pass; blocking. Never throws — errors land in [lastError]. */
    fun syncOnce(nowSec: Long, force: Boolean = false): Boolean {
        val c = client() ?: return false
        if (!c.signedIn) return false
        return try {
            val uid = c.ensureUid()
            drainEvents(c, uid)
            val corrected = applyPhoneCorrections(c, uid)
            // Every 2 minutes (and at once on stepping away), so the merged day stays current everywhere.
            if (force || corrected || nowSec - lastReportTs >= 120) { uploadReport(c, uid, LocalDate.now(zone)); lastReportTs = nowSec }
            if (force || nowSec - lastHeatTs >= 600) { uploadHeat(c, uid); lastHeatTs = nowSec }
            uploadScheduleIfChanged(c, uid)
            uploadCheckinFlag(c, uid)
            pullReactions(c, uid)
            if (force || nowSec - lastInfoTs >= 300) { refreshLinkInfo(c, uid); lastInfoTs = nowSec }
            lastError = null; lastSyncTs = nowSec; true
        } catch (e: Exception) { lastError = e.message; false }
    }

    fun linkInfo(): LinkInfo? = prefs.get("cloud.link_info")?.let { raw ->
        runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            fun strs(k: String) = (o[k] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            LinkInfo(prefs.get("cloud.account_name"), prefs.get("cloud.account_email"),
                (o["phones"] as? JsonArray)?.map { val p = it.jsonObject; p["name"]!!.jsonPrimitive.content to (p["seen"]!!.jsonPrimitive.longOrNull ?: 0) } ?: emptyList(),
                strs("partners"), strs("supporting"))
        }.getOrNull()
    } ?: if (signedIn) LinkInfo(prefs.get("cloud.account_name"), prefs.get("cloud.account_email"), emptyList(), emptyList(), emptyList()) else null

    /** Registers this Mac under the account and caches who it's linked to: account, phones, partners. */
    private fun refreshLinkInfo(c: CloudClient, uid: String) {
        c.write("users/$uid/devices", deviceId(), buildJsonObject {
            put("kind", "mac"); put("name", macName()); put("lastSyncMs", System.currentTimeMillis())
            // The server sends the daily report once you've been inactive for a while after work.
            lastActive()?.let { put("lastActiveMs", it * 1000) }
            put("linkedAt", (prefs.long("cloud.linked_at", System.currentTimeMillis() / 1000)) * 1000)
            put("appVersion", dev.ontask.cloud.UpdateCheck.currentVersion())
        })
        c.get("users/$uid")?.let { p ->
            p["displayName"]?.jsonPrimitive?.contentOrNull?.let { prefs.set("cloud.account_name", it) }
            p["email"]?.jsonPrimitive?.contentOrNull?.let { prefs.set("cloud.account_email", it) }
        }
        val phones = c.list("users/$uid/devices").map { it.second }.filter { it["kind"]?.jsonPrimitive?.contentOrNull == "phone" }
        val partners = c.queryEq("links", "trackedUid", uid).mapNotNull { it["partnerName"]?.jsonPrimitive?.contentOrNull }
        val supporting = c.queryEq("links", "partnerUid", uid).mapNotNull { it["trackedName"]?.jsonPrimitive?.contentOrNull }
        prefs.set("cloud.link_info", buildJsonObject {
            put("phones", buildJsonArray { phones.forEach { p -> add(buildJsonObject { put("name", p["name"]?.jsonPrimitive?.contentOrNull ?: "Phone"); put("seen", p["lastSeenMs"]?.jsonPrimitive?.longOrNull ?: 0) }) } })
            put("partners", buildJsonArray { partners.forEach { add(JsonPrimitive(it)) } })
            put("supporting", buildJsonArray { supporting.forEach { add(JsonPrimitive(it)) } })
        }.toString())
    }

    private fun deviceId(): String = prefs.get("cloud.device_id") ?: ("mac-" + java.util.UUID.randomUUID()).also { prefs.set("cloud.device_id", it) }

    /** The name in System Settings → General → About, e.g. "Dennis's MacBook Pro". */
    private fun macName(): String = runCatching {
        ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("not set") } ?: runCatching { java.net.InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrDefault("Mac")

    private fun drainEvents(c: CloudClient, uid: String) {
        val done = ArrayList<Long>()
        for (e in events.pending(50)) {
            c.write("users/$uid/events", null, buildJsonObject { put("type", e.type); put("ts", e.ts * 1000); put("payload", e.payload) })
            done.add(e.id)
        }
        if (done.isNotEmpty()) events.markSynced(done)
    }

    /** Corrections made on the phone ("Where the time went" → pick a category), applied here and then removed. */
    private fun applyPhoneCorrections(c: CloudClient, uid: String): Boolean {
        val reqs = c.list("users/$uid/labelRequests")
        reqs.forEach { (id, o) ->
            val name = o["subject"]?.jsonPrimitive?.contentOrNull; val cat = o["category"]?.jsonPrimitive?.contentOrNull
            val item = o["itemKey"]?.jsonPrimitive?.contentOrNull
            if (cat != null && cat in Category.registry) when {
                item != null -> relabelItem(item, o["title"]?.jsonPrimitive?.contentOrNull, cat)
                name != null -> relabel(name, cat)
            }
            c.delete("users/$uid/labelRequests/$id")
        }
        return reqs.isNotEmpty()
    }

    /** Whether you get your own daily report by email (on by default). */
    var reportEmail: Boolean
        get() = prefs.bool("cloud.report_email", true)
        set(v) { prefs.set("cloud.report_email", v) }

    /** Whether your partner may see the titles of pages and videos on Varies sites (otherwise only per-site totals). */
    var partnerSeesTitles: Boolean
        get() = prefs.bool("cloud.partner_titles", false)
        set(v) { prefs.set("cloud.partner_titles", v) }

    /**
     * This Mac's day report (page titles only if [partnerSeesTitles]) and its titled rows, each as this device's own
     * document. The server merges all devices' documents into the day everyone reads (reports/{date}, and reportItems
     * for the titles only you can read).
     */
    private fun uploadReport(c: CloudClient, uid: String, date: LocalDate) {
        val r = reports.daily(date)
        val payload = reportPayload(r, System.currentTimeMillis(), titles = partnerSeesTitles).let { p ->
            val (am, pm) = halves(date)
            val withHalves = if (am == null && pm == null) p else JsonObject(p + ("halves" to buildJsonObject { am?.let { put("morning", it) }; pm?.let { put("afternoon", it) } }))
            JsonObject(withHalves + ("device" to deviceJson()))
        }
        c.write("users/$uid/deviceReports", "${date}_${deviceId()}", payload)
        c.write("users/$uid/deviceReportItems", "${date}_${deviceId()}", buildJsonObject {
            put("date", date.toString()); put("device", deviceJson())
            put("topSites", sitesJson(r.topSubjects, titles = true)); put("updatedAt", System.currentTimeMillis())
        })
    }

    private fun deviceJson() = buildJsonObject { put("id", deviceId()); put("kind", "mac"); put("name", hostName()) }
    private fun hostName(): String = runCatching { java.net.InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrDefault("Mac")

    /**
     * "Where the time went" rows. With [titles], pages and videos on Varies sites are their own rows (with the key the
     * phone sends back to correct one); without, they're folded into one row per site and category ("YouTube").
     */
    fun sitesJson(subjects: List<dev.ontask.report.Subject>, titles: Boolean): JsonArray {
        val rows = if (titles) subjects else subjects
            .groupBy { (if (it.isItem) it.name.substringBefore(": ") else it.name) to it.category }
            .map { (k, v) -> dev.ontask.report.Subject(k.first, k.second, v.sumOf { it.seconds }) }
            .sortedByDescending { it.seconds }
        return buildJsonArray {
            rows.take(12).forEach { s ->
                add(buildJsonObject {
                    put("name", s.name); put("category", if (s.isItem && s.category.variable) "Unsure" else s.category.label); put("categoryId", s.category.name); put("seconds", s.seconds)
                    s.itemKey?.let { put("itemKey", it) }; s.title?.let { put("title", it) }
                })
            }
        }
    }

    private fun uploadScheduleIfChanged(c: CloudClient, uid: String) {
        val payload = schedulePayload()
        val hash = payload.toString().hashCode().toString()
        if (prefs.get("cloud.schedule_hash") == hash) return
        c.write("users/$uid/schedule", "current", payload)
        prefs.set("cloud.schedule_hash", hash)
    }

    /**
     * Today's plan, the midday check-in (got done, should have, what's in the way) and, once done, the evening check-in (how the day felt, what helped and hurt, the change for
     * tomorrow, a note for your partner): the server puts them in the daily report. Re-sent only when they change.
     */
    private fun uploadCheckinFlag(c: CloudClient, uid: String) {
        val today = LocalDate.now(zone)
        val ev = checkins.get(today, "evening")?.takeIf { it.sent }
        val md = checkins.get(today, "midday")
        val payload = buildJsonObject {
            put("evening", ev != null)
            put("plan", plan(today).joinToString("\n"))
            if (md != null) put("midday", buildJsonObject {
                md.helped?.let { put("done", it) }; md.hurt?.let { put("should", it) }; md.change?.let { put("chips", it) }
            })
            if (ev != null) {
                put("ts", ev.ts * 1000)
                ev.rating?.let { put("rating", it); put("feeling", dev.ontask.plan.CheckinDraft.feeling(it)) }
                ev.helped?.takeIf { it.isNotBlank() }?.let { put("helped", it) }
                ev.hurt?.takeIf { it.isNotBlank() }?.let { put("hurt", it) }
                ev.change?.takeIf { it.isNotBlank() }?.let { put("change", it) }
                ev.message?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            }
        }
        val hash = "$today:" + payload.toString().hashCode()
        if (prefs.get("cloud.checkin_hash") == hash) return
        c.write("users/$uid/checkins", today.toString(), payload)
        prefs.set("cloud.checkin_hash", hash)
    }

    /**
     * This Mac's numbers for each day of the heat map's year (all additive, so the server can add other devices'
     * and score the sum): users/{uid}/deviceHeat/{device}. The server writes the merged heat/year everyone reads.
     */
    private fun uploadHeat(c: CloudClient, uid: String) {
        val (days, _) = heat() ?: return
        val payload = heatPayload(days)
        val hash = payload.toString().hashCode().toString()
        if (prefs.get("cloud.heat_hash") == hash) return
        c.write("users/$uid/deviceHeat", deviceId(), JsonObject(payload + mapOf("device" to deviceJson(), "updatedAt" to kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))))
        prefs.set("cloud.heat_hash", hash)
    }

    fun heatPayload(days: Map<LocalDate, dev.ontask.report.DayStat>): JsonObject = buildJsonObject {
        put("days", buildJsonObject {
            days.filterValues { it.active > 0 }.toSortedMap().forEach { (d, s) ->
                put(d.toString(), buildJsonObject {
                    put("deep", s.deep); put("active", s.active); put("workDeep", s.workDeep); put("workActive", s.workActive)
                    put("offWasted", s.offWasted); put("offActive", s.offActive)
                })
            }
        })
    }

    private fun pullReactions(c: CloudClient, uid: String) {
        val after = prefs.long("cloud.reactions_ts", 0)
        val found = c.queryGroup("reactions", "trackedUid", uid, after)
        if (found.isEmpty()) return
        val all = (reactions().map { buildJsonObject { put("kind", it.kind); it.reply?.let { r -> put("reply", r) }; put("ts", it.ts) } } +
            found.map { buildJsonObject { put("kind", it["kind"]!!.jsonPrimitive.content); it["reply"]?.let { r -> put("reply", r.jsonPrimitive.content) }; put("ts", it["ts"]!!.jsonPrimitive.longOrNull ?: 0) } }).takeLast(20)
        prefs.set("cloud.reactions", JsonArray(all).toString())
        prefs.set("cloud.reactions_ts", (found.maxOf { it["ts"]!!.jsonPrimitive.longOrNull ?: 0 }).toString())
    }

    // ---- payloads (pure) ---------------------------------------------------------------------------------

    fun reportPayload(r: DailyReport, nowMs: Long, titles: Boolean = false): JsonObject = buildJsonObject {
        put("date", r.date.toString())
        // Timeline times are seconds after the day's start; with these the phone shows real clock times (right on
        // daylight-saving days, and for a partner in another time zone).
        put("dayStart", r.dayStart); put("tz", zone.id)
        put("totals", buildJsonObject { Level.entries.forEach { put(it.name, r.totalsAll[it] ?: 0) } })
        put("workTotals", buildJsonObject { Level.entries.forEach { put(it.name, r.totalsWork[it] ?: 0) } })
        put("timeline", buildJsonArray {
            // Coarsened: runs under a minute are dropped, capped to keep the document small.
            r.timeline.filter { it.seconds >= 60 }.take(500).forEach { run ->
                add(buildJsonObject {
                    put("a", run.start - r.dayStart); put("b", run.end - r.dayStart); put("k", run.level.name.first().toString())
                    put("c", dev.ontask.report.runLabel(run))
                    // What the stretch was made of, for the phone's timeline details.
                    // Without titles allowed, "youtube.com – Learning Java" is just "youtube.com".
                    run.top(3).map { (n, sec) -> (if (titles) n else n.substringBefore(" – ")) to sec }.groupBy({ it.first }, { it.second }).map { (n, s) -> n to s.sum() }
                        .takeIf { it.isNotEmpty() }?.let { top -> put("t", buildJsonArray { top.forEach { (n, sec) -> add(buildJsonObject { put("n", n); put("s", sec) }) } }) }
                })
            }
        })
        put("deepSessions", buildJsonArray { r.deepSessions.forEach { add(buildJsonObject { put("a", it.start - r.dayStart); put("b", it.end - r.dayStart) }) } })
        put("distractionEvents", r.distractionEvents)
        r.worstHour?.let { put("worstHour", buildJsonObject { put("hour", java.time.Instant.ofEpochSecond(r.dayStart + it.first * 3600L).atZone(zone).hour); put("wastedSec", it.second) }) }
        put("topSites", sitesJson(r.topSubjects, titles))
        put("updatedAt", nowMs)
    }

    fun schedulePayload(): JsonObject {
        val s = settings()
        return buildJsonObject {
            put("tz", zone.id)
            // Email yourself the daily report too (to your sign-in address).
            put("reportEmail", reportEmail)
            // Bedtime, so your phone gets a push then too (null = off).
            if (prefs.bool("bedtime.enabled", true)) put("bedtimeMin", prefs.int("bedtime.min", dev.ontask.plan.Bedtime.DEFAULT_MIN))
            put("workWeek", buildJsonObject {
                s.schedule.week.days.forEach { (d, ranges) -> put(d.toString(), buildJsonArray { ranges.forEach { add(buildJsonObject { put("startMin", it.startMin); put("endMin", it.endMin) }) } }) }
            })
            put("exceptions", buildJsonObject {
                s.schedule.exceptions.forEach { (d, ranges) -> put(d.toString(), buildJsonArray { ranges.forEach { add(buildJsonObject { put("startMin", it.startMin); put("endMin", it.endMin) }) } }) }
            })
            // So the phone can offer the same categories when you correct a label there.
            put("categories", buildJsonArray {
                Category.assignable.forEach { k -> add(buildJsonObject { put("id", k.name); put("label", k.label); put("level", k.level.name); put("variable", k.variable) }) }
            })
            put("habits", buildJsonArray {
                habits.all().forEach { h -> add(buildJsonObject { put("id", h.id); put("name", h.name); put("time", h.time); put("enabled", h.enabled); put("days", buildJsonArray { h.days.forEach { add(JsonPrimitive(it)) } }) }) }
            })
        }
    }
}
