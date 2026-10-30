package dev.ontask

import dev.ontask.block.*
import dev.ontask.plan.Priorities
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
                if (now - lastFlush >= FLUSH_EVERY_SEC) { tracker.flush(); lastFlush = now }
                if (now - lastRollup >= 3600) { Retention.rollup(db, now, settings.retentionDays); lastRollup = now }
                delay(settings.tickSec * 1000L)
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
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
