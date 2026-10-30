package dev.ontask

import dev.ontask.block.*
import dev.ontask.ai.AiService
import dev.ontask.ai.FewShot
import dev.ontask.intervene.*
import dev.ontask.plan.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import java.time.ZoneId
import dev.ontask.classify.Classifier
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import dev.ontask.db.Retention
import dev.ontask.db.RuleStore
import dev.ontask.db.Settings
import dev.ontask.db.SettingsStore
import dev.ontask.db.TickStore
import dev.ontask.report.Diagnostics
import dev.ontask.report.Reports
import dev.ontask.track.HelperClient
import dev.ontask.track.SystemMetrics
import dev.ontask.track.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.time.LocalDate

/** Wires the pieces together and owns the tracking loop. */
class App(val dir: Path = dataDir()) {
    val db: Db = Db.open(dir.resolve("ontask.db"))
    val prefs = Prefs(db)
    val ruleStore = RuleStore(db)
    val settingsStore = SettingsStore(db)
    val reports = Reports(db)
    val diagnostics = Diagnostics(db)
    val metrics = SystemMetrics()
    val helper = HelperClient(helperBinary())

    @Volatile var settings: Settings = settingsStore.load(); private set
    @Volatile private var classifier = Classifier(ruleStore.userRules())

    val events = Events(db)
    val blocklist = Blocklist(db)
    val unblocks = Unblocks(db, prefs, events)
    val focus = Focus(db, events)
    val blocking = BlockingService(dir, prefs, blocklist, unblocks, focus, settings = { settings })
    val workHours = WorkHoursEditor(settingsStore, events, { settings }) { settings = it }

    val priorities = Priorities(db)
    val habits = dev.ontask.plan.Habits(prefs)

    fun requestAccessibility() { scope.launch { helper.request("request_accessibility") } }

    /** The line shown on the delay screen: the user's own static message, or (if enabled) a random alternative. */
    fun unblockMessage(): String {
        val own = prefs.str("unblock.message", "Is this really what you want to be doing right now?")
        if (!prefs.bool("unblock.random", false)) return own
        val alts = prefs.str("unblock.alternatives", "").lines().filter { it.isNotBlank() }
        return if (alts.isEmpty()) own else alts.random()
    }

    val cloud by lazy { dev.ontask.cloud.CloudSync(prefs, events, reports, { settings }, habits, checkins) }
    val updates = dev.ontask.cloud.UpdateCheck()
    @Volatile var updateAvailable: dev.ontask.cloud.UpdateInfo? = null; private set
    private var lastCloud = 0L; private var lastUpdateCheck = 0L

    fun checkForUpdates(): dev.ontask.cloud.UpdateInfo? {
        val repo = prefs.get("update.repo") ?: return null
        updateAvailable = updates.check(repo, dev.ontask.cloud.UpdateCheck.currentVersion()); lastUpdateCheck = nowSec()
        return updateAvailable
    }

    val ai = AiService(prefs, db, dir.toFile())
    val checkins = Checkins(db)
    val ladder = Ladder()
    val alerts = AlertLimiter(2)

    private val _intervention = MutableStateFlow<Intervention?>(null)
    /** Current intervention step to show (null = none). Cleared by the user's action. */
    val intervention: StateFlow<Intervention?> = _intervention
    private val _duePrompt = MutableStateFlow<Prompt?>(null)
    val duePrompt: StateFlow<Prompt?> = _duePrompt
    private val _batteryPrompt = MutableStateFlow(false)
    val batteryPrompt: StateFlow<Boolean> = _batteryPrompt

    private val aiCache = object : LinkedHashMap<String, dev.ontask.ai.AiResult?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, dev.ontask.ai.AiResult?>) = size > 200
    }
    @Volatile private var aiBusy = false
    private var lastAi = 0L; private var lastBattery = 0L; private var lastRhythm = 0L
    private var batteryAsked = false
    private val snoozeUntil = HashMap<Prompt, Long>()

    val tracker = Tracker(TickStore(db), { classifier }, { settings })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        Retention.rollup(db, nowSec(), settings.retentionDays)
        if (!prefs.bool("blocklist.seeded", false)) { blocklist.seedDefault(nowSec()); prefs.set("blocklist.seeded", true) }
        tracker.seedTotals(LocalDate.now(), reports.totals(LocalDate.now()))
        scope.launch {
            var lastFlush = nowSec(); var lastRollup = nowSec()
            while (true) {
                val t0 = System.nanoTime()
                helper.sample()?.let { tracker.onSample(it, nowSec(), (System.nanoTime() - t0) / 1_000_000) }
                val now = nowSec()
                blocking.tick(now)
                onTick(now)
                if (now - lastFlush >= FLUSH_EVERY_SEC) { tracker.flush(); lastFlush = now }
                if (now - lastRollup >= 3600) { Retention.rollup(db, now, settings.retentionDays); lastRollup = now }
                delay(settings.tickSec * 1000L)
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    }

    /** Everything that happens once per tick beyond tracking: interventions, AI, battery, daily rhythm. */
    private fun onTick(now: Long) {
        val live = tracker.live.value
        val wasted = !live.away && live.classification?.level == Level.WASTED
        ladder.cfg = ladder.cfg.copy(lockSec = prefs.int("ladder.lock_sec", 30).coerceIn(30, 180), step1WastedSec = prefs.int("ladder.step1_sec", 180))
        val iv = ladder.tick(now, wasted, blocking.strictNow(now), unblocks.unblocksInLast(now, ladder.cfg.unblockWindowSec.toLong()))
        if (iv != null && prefs.bool("ladder.enabled", true)) _intervention.value = iv
        if (_intervention.value != null && ladder.step == Step.NONE) _intervention.value = null

        if (ai.enabled && !ai.metadataOnly && !live.away && now - lastAi >= prefs.int("ai.interval_sec", 30) && tracker.needsAi() && !aiBusy) { lastAi = now; scope.launch(IO) { aiCycle(live.sample) } }
        if (ai.llama.running) ai.llama.stopIfIdle()
        if (cloud.configured && now - lastCloud >= 60) { lastCloud = now; scope.launch(IO) { cloud.syncOnce(now) } }
        if (prefs.get("update.repo") != null && now - lastUpdateCheck >= 7 * 86400) { lastUpdateCheck = now; scope.launch(IO) { checkForUpdates() } }
        if (ai.enabled && now - lastBattery >= 60) { lastBattery = now; scope.launch(IO) { batteryCheck() } }
        if (now - lastRhythm >= 30) { lastRhythm = now; runCatching { rhythmCheck(now, live.away) } }
    }

    private suspend fun aiCycle(sample: dev.ontask.Sample?) {
        if (sample == null) return
        aiBusy = true
        try {
            val key = listOf(sample.app, sample.bundleId, dev.ontask.classify.domainOf(sample.url), sample.title?.take(40)).joinToString("|")
            val cached = synchronized(aiCache) { if (aiCache.containsKey(key)) aiCache[key] else null }
            if (cached != null) { tracker.applyAi(cached); return }
            val r = helper.request("ocr", 15000, mapOf("skipIfUnchanged" to false))
            val text = r?.get("text")?.jsonPrimitive?.contentOrNull
            val res = ai.classify(sample, text, priorities.today(nowSec()), ai.recentLabels(), screenshots = {
                runBlocking { helper.request("screenshot", 15000, mapOf("maxDim" to 1280)) }?.get("images")?.let { arr -> (arr as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content } }
            })
            if (res != null) { synchronized(aiCache) { aiCache[key] = res }; tracker.applyAi(res) }
        } catch (e: Exception) {
            aiError = e.message
        } finally { aiBusy = false }
    }
    @Volatile var aiError: String? = null; private set

    private suspend fun batteryCheck() {
        val r = helper.request("battery") ?: return
        val onBattery = r["onBattery"]?.jsonPrimitive?.booleanOrNull ?: false
        val pct = r["percent"]?.jsonPrimitive?.intOrNull ?: 100
        if (!onBattery) { batteryAsked = false; if (ai.metadataOnly && prefs.bool("ai.metadata_only_auto", false)) { ai.metadataOnly = false; prefs.set("ai.metadata_only_auto", false) } }
        else if (pct <= 20 && !batteryAsked && !ai.metadataOnly) { batteryAsked = true; _batteryPrompt.value = true }
    }

    fun answerBattery(metadataOnly: Boolean) { if (metadataOnly) { ai.metadataOnly = true; prefs.set("ai.metadata_only_auto", true) }; _batteryPrompt.value = false }

    private fun rhythmCheck(now: Long, away: Boolean) {
        val zone = ZoneId.systemDefault(); val date = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val (first, last) = reports.presence(date)
        val state = DayState(date, first, !away, priorities.forDate(date).isNotEmpty() || checkins.done(date, "planning"), checkins.done(date, "midday"), checkins.done(date, "evening"), last)
        val due = Rhythm(zone, prefs.bool("rhythm.midday", true), prefs.bool("rhythm.evening_at_end", true)).due(settings.schedule, state, now)
        when (due) {
            null -> {}
            Prompt.NO_COMPUTER -> markOnce("no_computer:$date") { checkins.save(Checkin(date, "evening", now, null, null, null, null, "No computer today", false)); events.emit("no_computer_day", now, "date" to date.toString()) }
            Prompt.EVENING_PHONE -> markOnce("evening_phone:$date") { events.emit("evening_checkin_phone", now, "date" to date.toString()) }
            else -> if (_duePrompt.value == null && now >= (snoozeUntil[due] ?: 0)) _duePrompt.value = due
        }
    }

    private fun markOnce(key: String, block: () -> Unit) { if (!prefs.bool("once.$key", false)) { block(); prefs.set("once.$key", true) } }

    /** The user dealt with (or snoozed) the daily prompt. Snoozed prompts come back with an escalating reminder event. */
    fun finishPrompt(p: Prompt, snoozeMin: Int? = null) {
        _duePrompt.value = null
        if (snoozeMin != null) {
            snoozeUntil[p] = nowSec() + snoozeMin * 60L
            events.emit("checkin_reminder", nowSec(), "kind" to p.name.lowercase())
        }
    }

    // ---- intervention actions ----------------------------------------------------------------------------

    fun interventionWork() {
        val s = tracker.live.value.sample; val c = tracker.live.value.classification
        if (s != null && c != null) {
            val domain = dev.ontask.classify.domainOf(s.url)
            val (kind, pattern) = if (domain != null) dev.ontask.classify.RuleKind.DOMAIN to domain else dev.ontask.classify.RuleKind.APP to (s.app ?: "")
            if (pattern.isNotBlank()) { ruleStore.label(kind, pattern, Category.CORE_WORK, c.category, nowSec()); reloadRules() }
        }
        ladder.workConfirmed(); _intervention.value = null
    }
    fun interventionBack() { ladder.backToWork(nowSec()); _intervention.value = null }
    fun interventionStruggling() { _intervention.value = ladder.struggling() }
    fun interventionDismiss() { _intervention.value = null }

    /** Sends the partner message through the daily rate limit; extras are queued into the next check-in. */
    fun sendPartnerAlert(text: String): Boolean {
        val day = java.time.LocalDate.now().toString()
        val ok = alerts.offer(day, text)
        if (ok) events.emit("binge_alert", nowSec(), "message" to text) else prefs.set("alerts.queued", (prefs.str("alerts.queued", "") + "\n" + text).trim())
        return ok
    }

    fun reloadRules() { classifier = Classifier(ruleStore.userRules()) }

    fun updateSettings(s: Settings) { settingsStore.save(s); settings = s }

    fun shutdown() {
        runCatching { tracker.flush() }
        helper.close()
    }

    companion object {
        const val FLUSH_EVERY_SEC = 30
        fun nowSec() = System.currentTimeMillis() / 1000

        fun dataDir(): Path = System.getProperty("ontask.data")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "Library", "Application Support", "OnTask")

        fun helperBinary(): File {
            System.getProperty("ontask.helper")?.let { return File(it) }
            System.getProperty("compose.application.resources.dir")?.let { File(it, "helper/ontask-helper").takeIf { f -> f.exists() }?.let { f -> return f } }
            return File("../helper/.build/release/ontask-helper")
        }
    }
}
