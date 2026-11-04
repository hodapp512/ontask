package dev.ontask.android.track

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ontask.Category
import dev.ontask.Sample
import dev.ontask.Source
import dev.ontask.android.Cloud
import dev.ontask.android.MainActivity
import dev.ontask.android.OnTaskApp
import dev.ontask.android.R
import dev.ontask.classify.Pages
import dev.ontask.classify.RuleKind
import dev.ontask.classify.domainOf
import dev.ontask.classify.itemKey
import dev.ontask.classify.siteDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sees what's in front on the phone: the app (window changes), and in browsers the address bar, in the YouTube app
 * the video's title. Every 5 seconds while the screen is on and unlocked it records that, classified with the
 * account's shared rules; it uploads the day every 2 minutes and when the screen turns off. Unknown apps and
 * undecided pages are judged by Jev on the server; when Jev isn't sure, a notification asks.
 */
class Tracker : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var log: DayLog
    lateinit var prompts: Prompts; private set
    /** What's in front now, for "I'm working": a rule key (APP|…, DOMAIN|…) or a page's item key. */
    @Volatile private var currentKey: String? = null
    private val borrow = dev.ontask.Borrow()
    /** What's been unsorted in front, and since when (for its screenshot). */
    private var shotSince: Pair<String, Long>? = null
    @Volatile private var pkg: String? = null
    @Volatile private var url: String? = null
    @Volatile private var title: String? = null
    private var lastRead = 0L
    private var lastUpload = 0L
    private val asked = HashSet<String>()
    private val judging = HashSet<String>()
    private var lastAsk = 0L
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { scope.launch { log.save(); runCatching { Uploader.upload(log, Cloud.deviceId(this@Tracker)) } } }
    }

    override fun onServiceConnected() {
        log = DayLog(this)
        Shots.prune(this)
        prompts = Prompts(this)
        running = this
        Cloud.uid?.let { PhoneRules.start(it) }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        scope.launch { while (isActive) { runCatching { tick() }; delay(TICK_MS) } }
    }

    override fun onDestroy() { running = null; prompts.stop(); runCatching { unregisterReceiver(screenOff) }; runCatching { log.save() }; scope.cancel(); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val p = e.packageName?.toString() ?: return
        if (p in ignored() || p == "android") return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (p != pkg) { pkg = p; url = null; title = null }
                read(p)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (p == pkg && System.currentTimeMillis() - lastRead > 1500) read(p)
        }
    }

    /** Keyboards and the system's own bars never count as "the app in front". */
    private fun ignored(): Set<String> = IGNORED + (getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName } ?: emptyList())

    private fun read(p: String) {
        lastRead = System.currentTimeMillis()
        val root = rootInActiveWindow ?: return
        BROWSER_BARS[p]?.let { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.contains(' ') }?.let { t ->
                url = if (t.startsWith("http")) t else "https://$t"
            }
            return
        }
        if (p == YOUTUBE) youTubeTitle(root)?.let { title = it }
    }

    /** The watch page's title in the YouTube app (null on the feed, Shorts and search). */
    private fun youTubeTitle(root: AccessibilityNodeInfo): String? =
        listOf("com.google.android.youtube:id/title", "com.google.android.youtube:id/video_title")
            .firstNotNullOfOrNull { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() } }

    private fun appLabel(p: String): String = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)

    private suspend fun tick() {
        val now = System.currentTimeMillis() / 1000
        Cloud.uid?.let { PhoneRules.start(it); prompts.start(it) }
        val power = getSystemService(PowerManager::class.java); val keyguard = getSystemService(KeyguardManager::class.java)
        val p = pkg
        if (power.isInteractive && !keyguard.isKeyguardLocked && p != null && p !in LAUNCHERS) {
            val app = appLabel(p)
            val u = url?.takeIf { p in BROWSER_BARS }
            // The YouTube app has no address: its videos are told apart by title.
            val t = title?.takeIf { p == YOUTUBE }
            val sample = Sample(app = app, bundleId = p, title = t, url = u)
            var cat = PhoneRules.classifier.classify(sample).category
            val domain = domainOf(u)?.let { siteDomain(it) }
            val subject = domain ?: app
            var item: String? = null
            if (cat.variable) {
                item = when {
                    p == YOUTUBE && t != null -> "youtube.com|$t"
                    u != null && !Pages.isFeed(u) -> itemKey(app, t, u)
                    else -> null
                }
                cat = when {
                    u != null && Pages.isFeed(u) -> Category.feed
                    p == YOUTUBE && t == null -> Category.feed  // the app's home feed and Shorts
                    item != null -> PhoneRules.verdict(item) ?: PhoneRules.sourceRule(Pages.sourceKey(u)) ?: cat.also { judgeLater(item, app, t, u, subject, isItem = true) }
                    else -> cat
                }
            } else if (cat == Category.UNKNOWN) judgeLater(if (domain != null) "DOMAIN|$domain" else "APP|$p", app, null, u, subject, isItem = false)
            val work = PhoneRules.isWork(now)
            // Still not sorted after 15 s (no category, or a page still Unsure): one screenshot for "Help sort these".
            val unsorted = cat == Category.UNKNOWN || (cat.variable && item != null)
            val shotKey = if (item != null) Shots.itemKey(item) else Shots.siteKey(subject)
            if (!unsorted) shotSince = null else if (shotSince?.first != shotKey) shotSince = shotKey to now
            else if (now - shotSince!!.second >= 15) Shots.take(this, shotKey)
            // Other (system pop-ups, search) counts as what came just before, for up to 2 minutes.
            val level = borrow.level(now, cat, away = false) ?: cat.level
            if (cat != Category.OTHER) borrow.solid(now, level)
            log.add(now, TICK_MS / 1000, p, app, subject, t, item, cat, work, level)
            currentKey = item ?: if (domain != null) "DOMAIN|$domain" else "APP|$p"
            prompts.lastSite = if (level == dev.ontask.Level.WASTED && cat != Category.OTHER) subject else prompts.lastSite
            prompts.tick(now, level, work)
        } else { borrow.level(now, Category.AWAY, away = true); prompts.tick(now, null, PhoneRules.isWork(now)) }
        prompts.bedtimeTick(now, power.isInteractive && !keyguard.isKeyguardLocked)
        prompts.checkinTick(power.isInteractive && !keyguard.isKeyguardLocked)
        // A new version (published with the web app) is offered by notification, checked every 6 hours.
        if (now - lastUpload >= 120) { lastUpload = now; log.save(); runCatching { Uploader.upload(log, Cloud.deviceId(this)) } }
    }

    /**
     * Asks Jev (through the server) about something no rule covers; files the answer for every device. When Jev
     * isn't sure (or there's no key), a notification asks you, at most every 2 minutes and once per thing per run.
     */
    private fun judgeLater(key: String, app: String, t: String?, u: String?, subject: String, isItem: Boolean) {
        synchronized(judging) { if (key in asked || !judging.add(key)) return }
        scope.launch {
            try {
                val allowed = getSharedPreferences("ontask", MODE_PRIVATE).getBoolean("jev.ok", false)
                val r = if (allowed) runCatching { Cloud.call("judgeSeen", mapOf("app" to app, "title" to t, "url" to u)) }.getOrNull() else null
                val cat = (r?.get("category") as? String)?.let { Category.registry[it] }
                android.util.Log.i("OnTask", "judged $key: ${cat?.name ?: "unsure"} (jev=$allowed, reply=$r)")
                if (cat != null) file(key, cat, t, Source.AI_PAGE)
                else ask(key, if (isItem) "${subject}: ${t ?: u ?: ""}" else subject, isItem)
            } finally { synchronized(judging) { judging.remove(key) } }
        }
    }

    /** Saves a decision: a rule for an app or site, a verdict for a page or video; today's stretches follow it. */
    fun file(key: String, cat: Category, t: String?, source: Source) {
        synchronized(asked) { asked.add(key) }
        if (!cat.variable) Shots.delete(this, when {
            key.startsWith("DOMAIN|") -> Shots.siteKey(key.substringAfter('|'))
            key.startsWith("APP|") -> Shots.siteKey(appLabel(key.substringAfter('|')))
            else -> Shots.itemKey(key)
        })
        if (key.startsWith("APP|") || key.startsWith("DOMAIN|")) {
            val kind = RuleKind.valueOf(key.substringBefore('|')); val pattern = key.substringAfter('|')
            PhoneRules.saveRule(kind, pattern, cat.name)
            log.recategorize({ s -> if (kind == RuleKind.APP) s.pkg == pattern && s.item == null else s.subject == pattern }, cat)
        } else {
            PhoneRules.saveVerdict(Cloud.deviceId(this), key, t, cat.name, source, null)
            log.recategorize({ it.item == key }, cat)
        }
    }

    /** A site, app, page or video from this drift, for "I'm actually on task": [pageKey] for a page on a Varies site. */
    data class DriftItem(val label: String, val seconds: Long, val siteKey: String, val pageKey: String?, val title: String?)

    /** What you were on (time-wasting) in the last [sec] seconds, longest first. */
    fun driftItems(sec: Long): List<DriftItem> {
        val since = System.currentTimeMillis() / 1000 - sec
        return log.snapshot().filter { it.end >= since && it.level == "WASTED" }
            .groupBy { it.item ?: it.subject }
            .map { (_, segs) ->
                val s = segs.first()
                val site = if ('.' in s.subject && ' ' !in s.subject) "DOMAIN|${s.subject}" else "APP|${s.pkg}"
                DriftItem(if (s.item != null) "${s.subject}: ${s.title ?: s.item}" else s.subject, segs.sumOf { it.end - it.start }, site, s.item, s.title)
            }.sortedByDescending { it.seconds }.take(8)
    }

    fun fileWork(key: String, title: String?) { Category.registry["CORE_WORK"]?.let { file(key, it, title, Source.USER_LABEL) } }

    /** "I'm working" from a prompt: what's in front is filed as Work. */
    fun markWork() { val k = currentKey ?: return; Category.registry["CORE_WORK"]?.let { file(k, it, title, Source.USER_LABEL) } }

    private fun ask(key: String, label: String, isItem: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastAsk < 120_000) { android.util.Log.i("OnTask", "not asking about $key yet (asked < 2 min ago)"); return }
        synchronized(asked) { if (!asked.add(key)) return }
        lastAsk = now
        val open = PendingIntent.getActivity(this, key.hashCode(), Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.ASK_KEY, key).putExtra(MainActivity.ASK_LABEL, label).putExtra(MainActivity.ASK_ITEM, isItem)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, OnTaskApp.CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (isItem) "Is this work?" else "Which category is this?").setContentText(label)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(this).notify(ASK_NOTIFICATION, n) }
    }

    companion object {
        const val TICK_MS = 5000L
        const val ASK_NOTIFICATION = 7001
        const val YOUTUBE = "com.google.android.youtube"
        @Volatile var running: Tracker? = null

        /** Browsers' address bars, by view id. */
        val BROWSER_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
        )
        /** Home screens: time on them isn't use of anything. */
        val LAUNCHERS = setOf("com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3")
        val IGNORED = setOf("com.android.systemui", "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin")

        fun isOn(ctx: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
