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
    /** Loaded first: rules and stored activity are read through the current list of categories. */
    val categories = dev.ontask.classify.CategoryStore(db).also { it.load() }
    val verdicts = dev.ontask.classify.Verdicts(db)
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
    val adBlock = AdBlock(dir, prefs)
    val blocking = BlockingService(dir, prefs, blocklist, unblocks, focus, adBlock = adBlock, settings = { settings })
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

    val cloud by lazy { dev.ontask.cloud.CloudSync(prefs, events, reports, { settings }, habits, checkins, relabel = { name, cat -> reclassify(name, Category.valueOf(cat)) }) }
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
    /** Corner nudge around a not-for-work unblock in work hours: when it starts, and when it runs out. */
    data class UnblockNudge(val ending: Boolean, val domains: List<String>, val minutes: Int, val countToday: Int)
    private val _unblockNudge = MutableStateFlow<UnblockNudge?>(null)
    val unblockNudge: StateFlow<UnblockNudge?> = _unblockNudge
    /** Open not-for-work unblocks (id → domain), to notice when they run out. */
    private val openRegular = HashMap<Long, String>()

    /** A not-for-work unblock in work hours: opens the sites, tells the partner (via the event) and nudges. */
    fun unblockRegular(domains: List<String>, minutes: Int, delayWaitedSec: Int) {
        val now = nowSec()
        val started = unblocks.startRegular(domains, minutes, now, delayWaitedSec)
        synchronized(openRegular) { started.forEach { openRegular[it.id] = it.domain } }
        blocking.tick(now)
        _unblockNudge.value = UnblockNudge(ending = false, domains, minutes, unblocks.todayCount(now))
    }

    /** Outside work hours: opens the sites at once, until [minutes] pass or work hours start. */
    fun unblockLeisure(domains: List<String>, minutes: Int) {
        val now = nowSec()
        unblocks.startLeisure(domains, minutes, now, settings.schedule.nextWorkStart(now))
        blocking.tick(now)
    }

    fun unblockNudgeOk() { _unblockNudge.value = null }
    fun unblockNudgeStruggling() { _unblockNudge.value = null; _intervention.value = ladder.struggling() }

    private fun checkRegularEnded(now: Long) {
        val active = unblocks.active(now).filter { it.kind == dev.ontask.block.UnblockKind.REGULAR }
        val ended = synchronized(openRegular) {
            active.forEach { openRegular.putIfAbsent(it.id, it.domain) } // also covers unblocks from before a restart
            val ids = active.map { it.id }.toSet()
            openRegular.filterKeys { it !in ids }.also { e -> e.keys.forEach { openRegular.remove(it) } }
        }
        if (ended.isNotEmpty()) _unblockNudge.value = UnblockNudge(ending = true, ended.values.distinct(), 0, unblocks.todayCount(now))
    }

    /** "Is this work?" for a page or video in a Variable category that AI couldn't decide. */
    data class VariableAsk(val item: String, val title: String?, val site: String?)
    private val _variableAsk = MutableStateFlow<VariableAsk?>(null)
    val variableAsk: StateFlow<VariableAsk?> = _variableAsk
    private val variableAsked = HashSet<String>()
    private val variableJudging = HashSet<String>()

    private val _duePrompt = MutableStateFlow<Prompt?>(null)
    val duePrompt: StateFlow<Prompt?> = _duePrompt
    private val _batteryPrompt = MutableStateFlow(false)
    val batteryPrompt: StateFlow<Boolean> = _batteryPrompt

    private val aiCache = object : LinkedHashMap<String, dev.ontask.ai.AiResult?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, dev.ontask.ai.AiResult?>) = size > 200
    }
    @Volatile private var aiBusy = false
    @Volatile private var adBlockBusy = false

    /** Re-downloads the ad-blocking list now (menu bar and settings). */
    private fun refreshAdBlock() { if (!adBlockBusy) { adBlockBusy = true; scope.launch(IO) { try { adBlock.refresh(nowSec()) } finally { adBlockBusy = false } } } }
    private var lastAi = 0L; private var lastBattery = 0L; private var lastRhythm = 0L
    private var batteryAsked = false
    private val snoozeUntil = HashMap<Prompt, Long>()

    val tracker = Tracker(TickStore(db), { classifier }, { settings })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        Retention.rollup(db, nowSec(), settings.retentionDays)
        if (!prefs.bool("blocklist.seeded", false)) { blocklist.seedDefault(nowSec()); prefs.set("blocklist.seeded", true) }
        tracker.seedTotals(LocalDate.now(), reports.totals(LocalDate.now()))
        // Ad/malware list: refreshed at startup (unless OnTask just restarted) and then every 24 hours in onTick.
        if (adBlock.enabled && nowSec() - adBlock.lastRefresh >= 3600) refreshAdBlock()
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
        variableCheck(now, live)
        checkRegularEnded(now)
        if (ai.llama.running) ai.llama.stopIfIdle()
        if (cloud.configured && now - lastCloud >= 60) { lastCloud = now; scope.launch(IO) { cloud.syncOnce(now) } }
        if (prefs.get("update.repo") != null && now - lastUpdateCheck >= 7 * 86400) { lastUpdateCheck = now; scope.launch(IO) { checkForUpdates() } }
        if (ai.enabled && now - lastBattery >= 60) { lastBattery = now; scope.launch(IO) { batteryCheck() } }
        if (adBlock.refreshDue(now) && !adBlockBusy) { adBlockBusy = true; scope.launch(IO) { try { adBlock.refresh(now) } finally { adBlockBusy = false } } }
        if (now - lastRhythm >= 30) { lastRhythm = now; runCatching { rhythmCheck(now, live.away) } }
    }

    /**
     * A page or video in a Variable category (e.g. YouTube) counts as Mixed until it's decided: a remembered verdict,
     * else AI on its title against today's plan, else — in work hours or a Focus session — a pop-up asking you.
     */
    private fun variableCheck(now: Long, live: dev.ontask.track.Live) {
        val seg = tracker.currentSegment() ?: return
        if (live.away || !seg.category.variable || seg.levelOverride != null) return
        val item = dev.ontask.classify.itemKey(seg.app, seg.title, seg.url)
        verdicts.get(item)?.let { tracker.applyVerdict(it); return }
        val ask = VariableAsk(item, seg.title, seg.domain ?: seg.app)
        synchronized(variableJudging) { if (!variableJudging.add(item)) return }
        if (ai.enabled && !ai.metadataOnly && live.sample != null) scope.launch(IO) {
            val work = ai.judgeWork(live.sample, priorities.today(now))
            if (work != null) { verdicts.set(item, seg.title, work, Source.AI_PAGE, nowSec()); tracker.applyVerdict(if (work) Level.DEEP else Level.WASTED) }
            else askVariable(ask, now)
        } else askVariable(ask, now)
    }

    private fun askVariable(ask: VariableAsk, now: Long) {
        synchronized(variableJudging) { variableJudging.remove(ask.item) }
        if (!blocking.strictNow(now) || _variableAsk.value != null || !variableAsked.add(ask.item)) return
        _variableAsk.value = ask
    }

    /** Answer from the pop-up or a dropdown: [work] true/false saves a verdict; null just closes the pop-up. */
    fun answerVariable(item: String, title: String?, work: Boolean?) {
        if (_variableAsk.value?.item == item) _variableAsk.value = null
        if (work == null) return
        verdicts.set(item, title, work, Source.USER_LABEL, nowSec())
        val seg = tracker.currentSegment()
        if (seg != null && dev.ontask.classify.itemKey(seg.app, seg.title, seg.url) == item) tracker.applyVerdict(if (work) Level.DEEP else Level.WASTED)
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

    /**
     * Files [name] (an app, or a website if it has a dot and no spaces) under [to]: saves a rule for the future and
     * relabels its past activity. Used by the dropdowns here and by corrections sent from the phone.
     */
    fun reclassify(name: String, to: Category, from: Category = Category.UNKNOWN) {
        val kind = if ('.' in name && ' ' !in name) dev.ontask.classify.RuleKind.DOMAIN else dev.ontask.classify.RuleKind.APP
        ruleStore.label(kind, name, to, from, nowSec())
        ruleStore.relabelHistory(kind, name, to)
        reloadRules(); tracker.invalidate()
    }

    /** Deletes a category and re-sorts what was in it with the remaining rules (anything unmatched → Unclassified). */
    fun deleteCategory(c: Category) {
        categories.delete(c) { app, bundle, title, url ->
            val cls = Classifier(ruleStore.userRules().filter { it.category != c })
            cls.classify(Sample(app = app, bundleId = bundle, title = title, url = url)).category
        }
        reloadRules(); tracker.invalidate()
    }

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
