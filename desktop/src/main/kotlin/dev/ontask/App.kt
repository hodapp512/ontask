package dev.ontask

import dev.ontask.block.*
import dev.ontask.ai.AiService
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

    val cloud by lazy { dev.ontask.cloud.CloudSync(prefs, events, reports, { settings }, habits, checkins, relabel = { name, cat -> reclassify(name, Category.valueOf(cat)) },
        relabelItem = { key, title, cat -> answerVariable(key, title, Category.valueOf(cat)) },
        lastActive = { lastActiveTs.takeIf { it > 0 } }, plan = { priorities.forDate(it) }, halves = { dayHalves(it) }) }
    /** Last moment you were at the Mac (not Away), for the daily report's "you've wrapped up" rule. */
    @Volatile private var lastActiveTs = 0L
    private var wasAway = false
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

    // ---- Blocking helper -------------------------------------------------------------------------------------

    val rootHelper = dev.ontask.block.RootHelper()
    private val _helperHealth = MutableStateFlow<dev.ontask.block.HelperHealth?>(null)
    /** Null until first checked; rechecked every minute. Irrelevant in test mode (see [helperNeeded]). */
    val helperHealth: StateFlow<dev.ontask.block.HelperHealth?> = _helperHealth
    private val _helperPrompt = MutableStateFlow(false)
    /** The "install the blocking helper" window: shown once per launch while the helper is missing or broken. */
    val helperPrompt: StateFlow<Boolean> = _helperPrompt
    private var helperPrompted = false
    private var lastHelperCheck = 0L
    @Volatile private var helperBusy = false
    /** Until when the Home banner about the helper is hidden: next launch, or 4 hours after dismissing it. */
    @Volatile var helperBannerHiddenUntil = 0L; private set

    /** True when blocking can't work: the helper needs installing or fixing (never in developer test mode). */
    fun helperNeeded(): Boolean = blocking.mode == dev.ontask.block.BlockMode.LIVE && _helperHealth.value.let { it != null && it != dev.ontask.block.HelperHealth.OK }

    fun checkHelper() {
        if (helperBusy) return
        helperBusy = true
        scope.launch(IO) {
            try {
                var h = rootHelper.health(blocking.stagedFile)
                // Possibly just staged, or the daemon missed it: ask it to apply once more before calling it broken.
                if (h == dev.ontask.block.HelperHealth.NOT_APPLYING) { blocking.reapply(); blocking.tick(nowSec()); delay(3000); h = rootHelper.health(blocking.stagedFile) }
                _helperHealth.value = h
                if (!helperPrompted && helperNeeded() && rootHelper.available && prefs.bool("setup.done", false)) { helperPrompted = true; _helperPrompt.value = true }
            } finally { helperBusy = false }
        }
    }

    /** Runs the installer (macOS asks for the admin password). Returns an error message, or null when it worked. */
    suspend fun installHelper(): String? = kotlinx.coroutines.withContext(IO) {
        val r = rootHelper.install(blocking.stagedFile, blocking.port)
        _helperPrompt.value = false
        blocking.reapply(); blocking.tick(nowSec())
        delay(1500)
        _helperHealth.value = rootHelper.health(blocking.stagedFile)
        when {
            r.ok -> null
            "-128" in r.output || "User canceled" in r.output -> "Not installed: the password prompt was cancelled."
            else -> "Couldn't install the helper: ${r.output}"
        }
    }

    fun helperPromptLater() { _helperPrompt.value = false }
    fun hideHelperBanner() { helperBannerHiddenUntil = nowSec() + 4 * 3600 }

    /**
     * Removes OnTask from this Mac: the helper and OnTask's lines in /etc/hosts (one admin prompt), the login item,
     * optionally the data folder, then moves the app to the Trash and quits. Returns an error message if the admin
     * step failed or was cancelled (nothing else is touched then).
     */
    fun uninstallOnTask(deleteData: Boolean): String? {
        val r = rootHelper.uninstall(target = "/etc/hosts")
        if (!r.ok) return if ("-128" in r.output || "User canceled" in r.output) "Cancelled: nothing was removed." else "Couldn't remove the blocking helper: ${r.output}"
        dev.ontask.block.LoginItem().set(atLogin = false, keepAlive = false)
        shutdown()
        if (deleteData) runCatching { dir.toFile().deleteRecursively() }
        dev.ontask.block.RootHelper.appBundle()?.let { b -> runCatching { java.awt.Desktop.getDesktop().moveToTrash(java.io.File(b)) } }
        kotlin.system.exitProcess(0)
    }

    fun unblockNudgeOk() { _unblockNudge.value = null }
    fun unblockNudgeStruggling() { _unblockNudge.value = null; _intervention.value = ladder.struggling()?.let { decorate(it, nowSec()) } }

    private fun checkRegularEnded(now: Long) {
        val active = unblocks.active(now).filter { it.kind == dev.ontask.block.UnblockKind.REGULAR }
        val ended = synchronized(openRegular) {
            active.forEach { openRegular.putIfAbsent(it.id, it.domain) } // also covers unblocks from before a restart
            val ids = active.map { it.id }.toSet()
            openRegular.filterKeys { it !in ids }.also { e -> e.keys.forEach { openRegular.remove(it) } }
        }
        if (ended.isNotEmpty()) _unblockNudge.value = UnblockNudge(ending = true, ended.values.distinct(), 0, unblocks.todayCount(now))
    }

    /** "I can't tell whether this is work" for a page or video on a Varies site that nothing could decide. */
    data class VariableAsk(val item: String, val title: String?, val label: String?, val sourceKey: String?)
    private val _variableAsk = MutableStateFlow<VariableAsk?>(null)
    val variableAsk: StateFlow<VariableAsk?> = _variableAsk
    /** Asked about this run (at most once each); still undecided after AI (shown as Unsure until you answer). */
    private val variableAsked = HashSet<String>()
    private val variableUnsure = HashMap<String, VariableAsk>()
    private val variableJudging = HashSet<String>()
    private val pageLookup = dev.ontask.classify.PageLookup()
    private var lastAskTs = 0L

    /** "Which category is this?" for a site or app no rule covers and AI couldn't place. [name] is what the rule will match. */
    data class UnknownAsk(val name: String)
    private val _unknownAsk = MutableStateFlow<UnknownAsk?>(null)
    val unknownAsk: StateFlow<UnknownAsk?> = _unknownAsk
    /** Where you've been on something unclassified since, and when "Not now" lets each be asked about again. */
    private var unknownSince: Pair<String, Long>? = null
    private val unknownNotNow = HashMap<String, Long>()

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

    /** Focus since your last break, and any break or lunch running (see [Breaks]). Guarded by itself. */
    val breaks = dev.ontask.intervene.Breaks()
    val tracker = Tracker(TickStore(db), { classifier }, { settings }, notWork = { synchronized(breaks) { breaks.active?.kind == dev.ontask.intervene.Breaks.Kind.LUNCH } })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        runCatching { migrateCategories() }
        runCatching { migrateSiteRules() }
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
        if (!live.away) lastActiveTs = now
        // Stepping away: sync now, so the server sees the final numbers and when you stopped.
        if (live.away && !wasAway && cloud.configured) { lastCloud = now; scope.launch(IO) { cloud.syncOnce(now, force = true) } }
        wasAway = live.away
        val wasted = !live.away && live.classification?.level == Level.WASTED
        if (wasted) driftSite()?.let { lastWastedSite = it }
        val (due, earned, onBreak) = synchronized(breaks) {
            breaks.forgetBefore(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond())
            Triple(breaks.tick(now, settings.tickSec.toLong(), if (live.away) null else live.classification?.level), breaks.earnedSec(), breaks.onBreak())
        }
        val lunchNow = synchronized(breaks) { breaks.active?.kind == dev.ontask.intervene.Breaks.Kind.LUNCH }
        if (wasLunch && !lunchNow) lunchSpan()?.let { prefs.set("lunch.span", "${LocalDate.now()}|${it.first}|$now") }
        wasLunch = lunchNow
        if (due != null) {
            if (due != dev.ontask.intervene.Breaks.Due.LUNCH_CHECK) ladder.breakOver(now)
            if (prefs.bool("ladder.enabled", true)) _breakCard.value = breakCard(due, now)
        }
        // With a break earned, the first prompt is "Is this a break?" after a minute; otherwise "drifting" after two.
        ladder.cfg = ladder.cfg.copy(lockSec = prefs.int("ladder.lock_sec", 30).coerceIn(30, 180), step1WastedSec = if (earned > 0) 60 else prefs.int("ladder.step1_sec", 120))
        val strict = blocking.strictNow(now)
        val iv = ladder.tick(now, wasted, (strict || beforeWorkAfterSitDown(now)) && !onBreak, unblocks.unblocksInLast(now, ladder.cfg.unblockWindowSec.toLong()))
            ?.let { decorate(it, now) }
        if (iv != null && prefs.bool("ladder.enabled", true)) { _intervention.value = iv; closedIv = null; if (iv.step.n >= 2) _breakCard.value = null }
        if (ladder.step == Step.NONE) { _intervention.value = null; closedIv = null }
        // A prompt closed with its window's close button comes back until it's answered.
        closedIv?.let { (c, at) -> if (now >= at && _intervention.value == null) { closedIv = null; if (ladder.step == c.step) _intervention.value = decorate(c, now) } }
        closedCard?.let { (c, at) -> if (now >= at && _breakCard.value == null) { closedCard = null; _breakCard.value = c } }

        if (ai.enabled && !ai.metadataOnly && !live.away && now - lastAi >= prefs.int("ai.interval_sec", 30) && tracker.needsAi() && !aiBusy) { lastAi = now; scope.launch(IO) { aiCycle(live.sample) } }
        variableCheck(now, live)
        unknownCheck(now, live)
        if (ai.enabled && !ai.metadataOnly && now - lastBackfill >= 120 && !backfillBusy) { lastBackfill = now; backfillBusy = true; scope.launch(IO) { try { judgeEarlier(now) } finally { backfillBusy = false } } }
        checkRegularEnded(now)
        if (ai.kev.running) ai.kev.stopIfIdle()
        if (cloud.configured && now - lastCloud >= 60) { lastCloud = now; scope.launch(IO) { cloud.syncOnce(now) } }
        if (prefs.get("update.repo") != null && now - lastUpdateCheck >= 7 * 86400) { lastUpdateCheck = now; scope.launch(IO) { checkForUpdates() } }
        if (ai.enabled && now - lastBattery >= 60) { lastBattery = now; scope.launch(IO) { batteryCheck() } }
        if (adBlock.refreshDue(now) && !adBlockBusy) { adBlockBusy = true; scope.launch(IO) { try { adBlock.refresh(now) } finally { adBlockBusy = false } } }
        if (now - lastBedtimeCheck >= 30) { lastBedtimeCheck = now; runCatching { bedtimeCheck(now, live) } }
        if (now - lastHelperCheck >= 60) { lastHelperCheck = now; checkHelper() }
        if (now - lastRhythm >= 30) { lastRhythm = now; runCatching { rhythmCheck(now, live.away) } }
    }

    /**
     * A page or video on a Varies site (e.g. YouTube) shows as Unsure until it's decided, in this order: a remembered
     * verdict; a rule learned from your answers for its source (subreddit, group, channel…); AI on its title, address
     * and looked-up facts; AI again with the text on screen; and finally a question to you. Feeds never get here:
     * they count as time-wasting from the start.
     */
    private fun variableCheck(now: Long, live: dev.ontask.track.Live) {
        val seg = tracker.currentSegment() ?: return
        if (live.away || !seg.category.variable || seg.itemCategory != null) return
        val item = dev.ontask.classify.itemKey(seg.app, seg.title, seg.url)
        verdicts.get(item)?.let { tracker.applyVerdict(it); return }
        synchronized(variableUnsure) { variableUnsure[item] }?.let { maybeAsk(it, now); return }
        synchronized(variableJudging) { if (!variableJudging.add(item)) return }
        val sample = live.sample ?: dev.ontask.Sample(app = seg.app, title = seg.title, url = seg.url)
        scope.launch(IO) {
            try {
                val (cat, key) = judgePage(item, seg.title, sample, now, readScreen = true)
                if (cat != null) tracker.applyVerdict(cat)
                else { val ask = VariableAsk(item, seg.title, seg.item, key); synchronized(variableUnsure) { variableUnsure[item] = ask }; maybeAsk(ask, nowSec()) }
            } finally { synchronized(variableJudging) { variableJudging.remove(item) } }
        }
    }

    /** Runs the pipeline for one page; saves and returns its category, or null (with its source) when still unsure. */
    private fun judgePage(item: String, title: String?, sample: dev.ontask.Sample, now: Long, readScreen: Boolean): Pair<Category?, String?> {
        val facts = runCatching { pageLookup.lookup(sample.url) }.getOrDefault(emptyMap())
        val key = dev.ontask.classify.Pages.sourceKey(sample.url, facts["Channel"])
        verdicts.ruleFor(key)?.let { verdicts.set(item, title, it, Source.RULE, key, now); return it to key }
        if (!ai.enabled) return null to key
        val plan = priorities.today(now)
        ai.judgeItem(sample, plan, facts)?.takeIf { it.probability >= ai.threshold }?.let { verdicts.set(item, title, it.category, Source.AI_PAGE, key, now); return it.category to key }
        if (readScreen && !ai.metadataOnly) {
            val text = runBlocking { helper.request("ocr", 15000, mapOf("skipIfUnchanged" to false)) }?.get("text")?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) ai.judgeItem(sample, plan, facts, text)?.takeIf { it.probability >= ai.threshold }
                ?.let { verdicts.set(item, title, it.category, Source.AI_PAGE, key, now); return it.category to key }
        }
        return null to key
    }

    /** Until when the "I can't tell" question is paused (0 = not paused). */
    val askPausedUntil: Long get() = prefs.long("ask.paused_until", 0L).takeIf { it > nowSec() } ?: 0L
    fun pauseAsking(minutes: Int) { prefs.set("ask.paused_until", nowSec() + minutes * 60L); _variableAsk.value = null; _unknownAsk.value = null }
    fun resumeAsking() { prefs.set("ask.paused_until", 0L) }

    /** Asks about the page you're on: at most once per page, at most every 2 minutes, and not while paused. */
    private fun maybeAsk(ask: VariableAsk, now: Long) {
        if (_variableAsk.value != null || askPausedUntil > 0 || now - lastAskTs < 120) return
        synchronized(variableAsked) { if (!variableAsked.add(ask.item)) return }
        lastAskTs = now
        _variableAsk.value = ask
    }

    /**
     * Unclassified means OnTask can't tell whether you're on task, so after 15 seconds on a site or app no rule covers
     * (and once AI has had its try) it asks you which category it is. The answer becomes a rule and fixes its past
     * activity. "Not now" asks again in 15 minutes; Pause asking holds this question too.
     */
    private fun unknownCheck(now: Long, live: dev.ontask.track.Live) {
        val seg = tracker.currentSegment()
        val name = seg?.takeIf { !live.away && tracker.needsAi() }?.let { s -> s.domain?.let { d -> dev.ontask.classify.siteDomain(d) } ?: s.app }
        if (name == null) { unknownSince = null; return }
        if (unknownSince?.first != name) unknownSince = name to now
        val aiPending = ai.enabled && !ai.metadataOnly && lastAi < unknownSince!!.second
        if (now - unknownSince!!.second < 15 || aiPending || aiBusy || _unknownAsk.value != null || _variableAsk.value != null || askPausedUntil > 0) return
        if (now < (unknownNotNow[name] ?: 0)) return
        _unknownAsk.value = UnknownAsk(name)
    }

    /** The window's close button: asked again in a minute if you're still on it. */
    fun unknownClosed(name: String) { _unknownAsk.value = null; unknownNotNow[name] = nowSec() + 60 }

    /** Your answer to "Which category is this?": files the site or app under [cat], or (null, "Not now") asks again later. */
    fun answerUnknown(name: String, cat: Category?) {
        _unknownAsk.value = null
        if (cat == null) { unknownNotNow[name] = nowSec() + 15 * 60; return }
        reclassify(name, cat)
    }

    /** The "I can't tell" window's close button: asked again in a minute if you're still on that page. */
    fun variableClosed(item: String) {
        if (_variableAsk.value?.item == item) _variableAsk.value = null
        synchronized(variableAsked) { variableAsked.remove(item) }
        lastAskTs = nowSec() - 60
    }

    private var lastBackfill = 0L
    @Volatile private var backfillBusy = false
    /** Items the catch-up already tried this run (still unsure, or AI failed), so it moves on to others. */
    private val backfillTried = HashSet<String>()

    /** Catches up on pages and videos from the last week that were never decided, a few at a time, without asking. */
    private fun judgeEarlier(now: Long) {
        val todo = verdicts.unjudged(now - 7 * 86400, 200).filter { it.first !in backfillTried }.take(5)
        for ((item, sample) in todo) {
            if (synchronized(variableJudging) { item in variableJudging }) continue
            backfillTried += item
            judgePage(item, sample.title, sample, now, readScreen = false)
        }
    }

    /** Your answer (the question window or a row's dropdown): [cat] decides the page; null ("Not now") leaves it Unsure. */
    fun answerVariable(item: String, title: String?, cat: Category?, sourceKey: String? = null) {
        if (_variableAsk.value?.item == item) _variableAsk.value = null
        if (cat == null) return
        val key = sourceKey ?: synchronized(variableUnsure) { variableUnsure[item]?.sourceKey }
        verdicts.set(item, title, cat, Source.USER_LABEL, key, nowSec())
        synchronized(variableUnsure) { variableUnsure.remove(item) }
        val seg = tracker.currentSegment()
        if (seg != null && dev.ontask.classify.itemKey(seg.app, seg.title, seg.url) == item) tracker.applyVerdict(cat)
    }

    private suspend fun aiCycle(sample: dev.ontask.Sample?) {
        if (sample == null) return
        aiBusy = true
        try {
            val key = listOf(sample.app, sample.bundleId, dev.ontask.classify.domainOf(sample.url), sample.title?.take(40)).joinToString("|")
            val cached = synchronized(aiCache) { if (aiCache.containsKey(key)) aiCache[key] else null }
            if (cached != null) { tracker.applyAi(cached); return }
            val text = if (ai.metadataOnly) null else helper.request("ocr", 15000, mapOf("skipIfUnchanged" to false))?.get("text")?.jsonPrimitive?.contentOrNull
            val res = ai.classify(sample, text, priorities.today(nowSec()))?.takeIf { it.probability >= ai.threshold }
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
        firstPresence = date to first
        val state = DayState(date, first, !away, priorities.forDate(date).isNotEmpty() || checkins.done(date, "planning"), checkins.done(date, "midday"), checkins.done(date, "evening"), last)
        val due = Rhythm(zone, prefs.bool("rhythm.midday", true), prefs.bool("rhythm.evening_at_end", true), prefs.int("rhythm.evening_lead_min", 30)).due(settings.schedule, state, now)
        when (due) {
            null -> {}
            Prompt.NO_COMPUTER -> markOnce("no_computer:$date") { checkins.save(Checkin(date, "evening", now, null, null, null, null, "No computer today", false)); events.emit("no_computer_day", now, "date" to date.toString()) }
            Prompt.EVENING_PHONE -> markOnce("evening_phone:$date") { events.emit("evening_checkin_phone", now, "date" to date.toString()) }
            else -> if (_duePrompt.value == null && now >= (snoozeUntil[due] ?: 0)) _duePrompt.value = due
        }
    }

    /** Each daily prompt can be put off twice a day ("Later", or closing its window); then it stays up. */
    private val snoozes = HashMap<Pair<Prompt, LocalDate>, Int>()
    fun snoozesLeft(p: Prompt): Int = 2 - (snoozes[p to LocalDate.now()] ?: 0)

    // ---- Midday check-in --------------------------------------------------------------------------------------

    /** Today's lunch (start, end), if you took one; the end is a guess (45 min) while it's running. */
    fun lunchSpan(date: LocalDate = LocalDate.now()): Pair<Long, Long>? = prefs.str("lunch.span", "").split('|').takeIf { it.size == 3 && it[0] == date.toString() }
        ?.let { it[1].toLong() to it[2].toLong() }

    /** The morning in numbers: until lunch, or until now. Breaks are left out of the "last hour". */
    fun morningNow(): dev.ontask.report.Morning {
        val now = nowSec()
        return dev.ontask.report.morning(reports.daily(LocalDate.now()), lunchSpan()?.first ?: now, synchronized(breaks) { breaks.intervals(now) })
    }

    /** "Morning 64% focused · Afternoon 48%": split at lunch, or at noon without one. */
    fun dayHalves(date: LocalDate): Pair<Int?, Int?> {
        val noon = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond()
        val l = lunchSpan(date)
        return dev.ontask.report.halves(reports.daily(date), l?.first ?: noon, l?.second ?: noon)
    }

    /** "Low energy" → a 10-minute break away from the screen; drift prompts stay quiet meanwhile. */
    fun startRestBreak() { synchronized(breaks) { breaks.startBreakFor(nowSec(), 600) }; events.emit("break_start", nowSec(), "minutes" to 10); ladder.reset() }

    private fun markOnce(key: String, block: () -> Unit) { if (!prefs.bool("once.$key", false)) { block(); prefs.set("once.$key", true) } }

    /** The user dealt with (or snoozed) the daily prompt. Snoozed prompts come back on the Mac after [snoozeMin]. */
    fun finishPrompt(p: Prompt, snoozeMin: Int? = null) {
        if (snoozeMin != null && snoozesLeft(p) <= 0) return // snoozed twice: it stays until answered
        _duePrompt.value = null
        if (snoozeMin != null) {
            snoozeUntil[p] = nowSec() + snoozeMin * 60L
            snoozes.merge(p to LocalDate.now(), 1, Int::plus)
            // Logged only: you're at the Mac and it comes back here, so nothing goes to the phone.
            events.emit("checkin_snoozed", nowSec(), "kind" to p.name.lowercase(), "minutes" to snoozeMin)
        }
    }

    // ---- intervention actions ----------------------------------------------------------------------------

    fun interventionWork() {
        val s = tracker.live.value.sample; val c = tracker.live.value.classification
        val seg = tracker.currentSegment()
        // On a Varies site (YouTube…) it's this page or video that's work, not the whole site.
        if (seg != null && seg.category.variable) answerVariable(dev.ontask.classify.itemKey(seg.app, seg.title, seg.url), seg.title, Category.CORE_WORK)
        else if (s != null && c != null) {
            val domain = dev.ontask.classify.domainOf(s.url)
            val (kind, pattern) = if (domain != null) dev.ontask.classify.RuleKind.DOMAIN to domain else dev.ontask.classify.RuleKind.APP to (s.app ?: "")
            if (pattern.isNotBlank()) { ruleStore.label(kind, pattern, Category.CORE_WORK, c.category, nowSec()); reloadRules() }
        }
        ladder.workConfirmed(); _intervention.value = null; closedIv = null
    }
    fun interventionBack() { ladder.backToWork(nowSec()); _intervention.value = null; closedIv = null }

    /** Fills in what the prompts say about breaks: earned, focused since the last one, Lunch, totals. */
    private fun decorate(iv: Intervention, now: Long): Intervention = synchronized(breaks) {
        val t = java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault())
        iv.copy(
            partnerAllowed = blocking.strictNow(now), site = lastWastedSite,
            breakSec = if (iv.step == Step.PROMPT) breaks.earnedSec() else 0, focusedSec = breaks.focusedSec, toEarnSec = breaks.toEarnSec(),
            lunch = iv.step == Step.PROMPT && breaks.lunchOffered(t.toLocalDate().toString(), t.hour * 60 + t.minute),
            breaksHourSec = breaks.breakSec(now, now - 3600), breaksTodaySec = breaks.breakSec(now, t.toLocalDate().atStartOfDay(t.zone).toEpochSecond()),
        )
    }

    /** "Back to work: 25 min Focus" (or the 10-minute "just start"): starts Focus, which blocks distractions. */
    fun interventionFocus(minutes: Int = 25) {
        val now = nowSec()
        synchronized(breaks) { breaks.end(now) }
        focus.start(minutes, now)
        scope.launch(IO) { blocking.tick(nowSec()) }
        _breakCard.value = null; closedCard = null
        interventionBack()
    }

    /** "Yes, 5-min break": the earned length, with no drift prompts until it's over. */
    fun interventionBreak() { synchronized(breaks) { breaks.startBreak(nowSec()) }; events.emit("break_start", nowSec()); ladder.reset(); _intervention.value = null; closedIv = null }

    /** Lunch: up to 45 minutes, once a day between 11:00 and 2:00. */
    fun interventionLunch() {
        val now = nowSec()
        synchronized(breaks) { breaks.startLunch(now, LocalDate.now().toString()) }
        prefs.set("lunch.span", "${LocalDate.now()}|$now|${now + 45 * 60}")
        events.emit("lunch_start", now); ladder.reset(); _intervention.value = null; closedIv = null
    }

    /** The prompt's own close button: it comes back in 30 seconds while you're still drifting. */
    fun interventionClosed() { _intervention.value?.let { closedIv = it to nowSec() + 30 }; _intervention.value = null }
    private var closedIv: Pair<Intervention, Long>? = null
    private var wasLunch = false

    /** "That's 5 minutes. Back to work?", "Is lunch over?" and "Lunch is over". */
    data class BreakCard(val due: dev.ontask.intervene.Breaks.Due, val minutes: Long, val breaksHourSec: Long, val breaksTodaySec: Long, val canExtend: Boolean)
    private val _breakCard = MutableStateFlow<BreakCard?>(null)
    val breakCard: StateFlow<BreakCard?> = _breakCard
    private var closedCard: Pair<BreakCard, Long>? = null

    private fun breakCard(due: dev.ontask.intervene.Breaks.Due, now: Long): BreakCard = synchronized(breaks) {
        val last = breaks.lastTaken()
        BreakCard(
            due, if (due == dev.ontask.intervene.Breaks.Due.BREAK_OVER) ((last?.let { it.second - it.first } ?: 0) + 30) / 60 else (now - (breaks.active?.start ?: now)) / 60,
            breaks.breakSec(now, now - 3600), breaks.breakSec(now, LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()),
            canExtend = due == dev.ontask.intervene.Breaks.Due.LUNCH_CHECK || due == dev.ontask.intervene.Breaks.Due.BREAK_OVER && breaks.canExtend(now),
        )
    }

    /** "5 more minutes" (once per break) or "15 more minutes" of lunch. */
    fun breakMore() { synchronized(breaks) { breaks.extend(nowSec()) }; ladder.reset(); _breakCard.value = null; closedCard = null }
    /** "just close": no Focus, but drifting on still escalates. */
    fun breakCardDone() { if (_breakCard.value?.due == dev.ontask.intervene.Breaks.Due.LUNCH_CHECK) synchronized(breaks) { breaks.end(nowSec()) }; ladder.breakOver(nowSec()); _breakCard.value = null; closedCard = null }
    fun breakCardClosed() { _breakCard.value?.let { closedCard = it to nowSec() + 30 }; _breakCard.value = null }

    /** The last site you were drifting on, named by the prompts' block button even after you've switched away. */
    @Volatile private var lastWastedSite: String? = null

    /** The site you're drifting on, for the prompts' "Block YouTube for 15 minutes" (whole site, e.g. youtube.com). */
    fun driftSite(): String? = dev.ontask.classify.domainOf(tracker.live.value.sample?.url)?.let { dev.ontask.classify.siteDomain(it) }

    /** "Block <site> for 15 minutes": blocks it now and counts as "Back to work". Your partner isn't told. */
    fun interventionBlock(domain: String, minutes: Int = 15) {
        val now = nowSec()
        blocking.blockFor(domain, minutes, now)
        events.emit("block_for_a_while", now, "domain" to domain, "minutes" to minutes)
        scope.launch(IO) { blocking.tick(nowSec()) }
        interventionBack()
    }
    fun interventionStruggling() { _intervention.value = ladder.struggling()?.let { decorate(it, nowSec()) } }

    /** Today's first moment at the computer (refreshed every 30 s by the rhythm check). */
    @Volatile private var firstPresence: Pair<LocalDate, Long?>? = null

    /**
     * On a work day, from when you first sit down until work hours start, drifting gets the same prompts as during work
     * hours (but no partner messages, and unblocking stays relaxed).
     */
    private fun beforeWorkAfterSitDown(now: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val t = java.time.Instant.ofEpochSecond(now).atZone(zone)
        val (date, first) = firstPresence ?: return false
        if (date != t.toLocalDate() || first == null || first > now) return false
        val start = settings.schedule.rangesFor(date).minOfOrNull { it.startMin } ?: return false
        return t.hour * 60 + t.minute < start
    }

    // ---- Bedtime -----------------------------------------------------------------------------------------------

    /** A bedtime reminder on screen: the [level]th tonight, [minutesPast] bedtime; from the third on it can't be closed at once. */
    data class BedtimeNudge(val level: Int, val minutesPast: Int, val lockSec: Int, val bedtime: String)
    private val _bedtime = MutableStateFlow<BedtimeNudge?>(null)
    val bedtimeNudge: StateFlow<BedtimeNudge?> = _bedtime
    private var lastBedtimeCheck = 0L

    val bedtimeEnabled get() = prefs.bool("bedtime.enabled", true)
    val bedtimeMin get() = prefs.int("bedtime.min", dev.ontask.plan.Bedtime.DEFAULT_MIN)

    private fun bedState(): dev.ontask.plan.Bedtime.NightState? = prefs.get("bedtime.state")?.split("|")?.takeIf { it.size == 4 }?.let {
        runCatching { dev.ontask.plan.Bedtime.NightState(LocalDate.parse(it[0]), it[1].toInt(), it[2].toLong(), it[3].toLong()) }.getOrNull()
    }
    private fun saveBedState(s: dev.ontask.plan.Bedtime.NightState) = prefs.set("bedtime.state", "${s.night}|${s.count}|${s.lastTs}|${s.snoozeUntil}")

    /** At bedtime, and every 15 minutes after while you're still at the computer and not working. */
    private var bedAwaySince = 0L

    private fun bedtimeCheck(now: Long, live: dev.ontask.track.Live) {
        // A reminder nobody answered doesn't wait until morning: it closes once you've been away 10 minutes (you went
        // to bed) or when the night ends at 5 AM.
        if (_bedtime.value != null) {
            bedAwaySince = if (live.away) bedAwaySince.takeIf { it > 0 } ?: now else 0L
            if (!bedtimeEnabled || dev.ontask.plan.Bedtime(bedtimeMin).nightOf(now) == null || (bedAwaySince > 0 && now - bedAwaySince >= 600)) { _bedtime.value = null; bedAwaySince = 0L }
            return
        }
        if (!bedtimeEnabled) return
        val b = dev.ontask.plan.Bedtime(bedtimeMin)
        val st = bedState()
        val n = b.due(now, active = !live.away, state = st) ?: return
        val prev = st?.takeIf { it.night == n.night } ?: dev.ontask.plan.Bedtime.NightState(n.night)
        saveBedState(prev.copy(count = n.level, lastTs = now))
        events.emit("bedtime_nudge", now, "level" to n.level, "minutes_past" to n.minutesPast)
        val label = java.time.LocalTime.of(bedtimeMin / 60, bedtimeMin % 60).format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
        _bedtime.value = BedtimeNudge(n.level, n.minutesPast, n.lockSec, label)
    }

    /** "Going to bed": closes the reminder. If you keep going anyway, the next one still comes. */
    fun bedtimeGoingToBed() {
        _bedtime.value = null
    }

    /** "10 more minutes" / "I'm working": no reminders until then. */
    fun bedtimeSnooze(minutes: Int) {
        val now = nowSec()
        bedState()?.let { saveBedState(it.copy(snoozeUntil = now + minutes * 60L)) }
        _bedtime.value = null
    }

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
        // A site rule covers the whole site: moving "en.m.wikipedia.org" means all of wikipedia.org.
        val pattern = if (kind == dev.ontask.classify.RuleKind.DOMAIN) dev.ontask.classify.siteDomain(name) else name
        ruleStore.label(kind, pattern, to, from, nowSec())
        ruleStore.relabelHistory(kind, pattern, to)
        reloadRules(); tracker.invalidate()
    }

    /** Deletes a category and re-sorts what was in it with the remaining rules (anything unmatched → Unclassified). */
    /**
     * Once after upgrading to the four default categories: old default names you kept are renamed, and anything still
     * filed under a retired built-in (Learning, Communication, Social, News, Shopping) is sorted again like a delete.
     */
    private fun migrateCategories() {
        if (prefs.bool("categories.v2", false)) return
        categories.renameOldDefaults()
        categories.retiredInUse().forEach { deleteCategory(Category(it, it.lowercase().replaceFirstChar { c -> c.uppercase() }, Level.MIXED)) }
        prefs.set("categories.v2", true)
    }

    /** Once: site rules saved for a language or mobile address ("en.m.wikipedia.org") are widened to the whole site. */
    private fun migrateSiteRules() {
        if (prefs.bool("rules.site_wide", false)) return
        ruleStore.userRules().filter { it.kind == dev.ontask.classify.RuleKind.DOMAIN && dev.ontask.classify.siteDomain(it.pattern) != it.pattern }.forEach { r ->
            ruleStore.delete(r.id)
            if (r.category != Category.UNKNOWN) reclassify(r.pattern, r.category)
        }
        prefs.set("rules.site_wide", true)
    }

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
