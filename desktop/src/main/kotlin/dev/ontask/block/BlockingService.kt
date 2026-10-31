package dev.ontask.block

import dev.ontask.db.Db
import dev.ontask.db.Prefs
import dev.ontask.db.Settings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * LIVE: the root helper applies the hosts file to /etc/hosts — always, once it's installed. DRY_RUN is for
 * developing OnTask only (nothing is blocked): set the `blocking.test_mode` preference or ONTASK_BLOCKING_TEST=1.
 */
enum class BlockMode { DRY_RUN, LIVE }

/**
 * Decides what should be blocked right now (work hours or Focus session, minus active unblocks), stages the hosts
 * file and — in LIVE mode only — tells the root daemon to apply it.
 */
class BlockingService(
    private val dir: Path,
    private val prefs: Prefs,
    val blocklist: Blocklist,
    val unblocks: Unblocks,
    val focus: Focus,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    val adBlock: AdBlock? = null,
    private val settings: () -> Settings,
) {
    /** File the daemon copies to /etc/hosts. */
    val stagedFile: Path get() = dir.resolve("hosts")
    /** In DRY_RUN this stands in for /etc/hosts so the whole path can be inspected. */
    val dryRunTarget: Path get() = dir.resolve("hosts.dry-run-applied")

    private var lastApplied: String? = null
    /** What [lastApplied] was built from, so the (large, with ad blocking) hosts file is only rebuilt on change. */
    private var lastKey: Any? = null
    @Volatile var lastError: String? = null; private set

    var mode: BlockMode
        get() = if (System.getenv("ONTASK_BLOCKING_TEST") == "1" || prefs.bool("blocking.test_mode", false)) BlockMode.DRY_RUN else BlockMode.LIVE
        set(v) { prefs.set("blocking.test_mode", v == BlockMode.DRY_RUN); reapply() }

    /** Forgets what was last applied, so the next [tick] stages and applies the hosts file again (e.g. after installing the helper). */
    @Synchronized
    fun reapply() { lastApplied = null; lastKey = null }
    val port: Int get() = prefs.int("blocking.port", 12901)

    /** Strict mode — unblocking has friction and the partner is told: work hours, or a running Focus session. */
    fun strictNow(now: Long): Boolean = settings().schedule.isWork(now, zone) || focus.active(now) != null

    /** The block list minus anything unblocked right now; applies around the clock (work hours only change the friction). */
    fun currentlyBlocked(now: Long): List<String> {
        val open = unblocks.active(now).map { it.domain }.toSet()
        return blocklist.all().filter { it !in open }
    }

    /** Idempotent; cheap enough to call every tick. Returns true if a new hosts file was written. */
    @Synchronized
    fun tick(now: Long): Boolean {
        focus.settle(now)
        // An evening unblock never carries into work hours or a Focus session.
        if (strictNow(now)) unblocks.active(now).filter { it.kind == UnblockKind.LEISURE }.forEach { unblocks.end(it.id, now, "work_started") }
        val blocked = currentlyBlocked(now)
        val key = blocked to (adBlock?.let { if (it.enabled) it.version else -1 } ?: -1)
        if (lastApplied != null && key == lastKey) return false
        val content = HostsBuilder.build(blocked, adBlock?.section() ?: emptyList())
        if (content == lastApplied) { lastKey = key; return false }
        return try {
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, "hosts", ".tmp")
            Files.writeString(tmp, content)
            Files.move(tmp, stagedFile, StandardCopyOption.REPLACE_EXISTING)
            lastApplied = content; lastKey = key
            when (mode) {
                BlockMode.DRY_RUN -> Files.writeString(dryRunTarget, content)
                // Without the helper the file stays staged: the daemon applies it as soon as it's installed and starts.
                BlockMode.LIVE -> notifyDaemon()
            }
            lastError = null; true
        } catch (e: Exception) {
            lastError = e.message; false
        }
    }

    private fun notifyDaemon() {
        var last: IOException? = null
        repeat(3) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 1000); s.getOutputStream().apply { write("copy\n".toByteArray()); flush() } }
                return
            } catch (e: IOException) { last = e; Thread.sleep(250) }
        }
        throw IOException("Could not reach OnTask's blocking helper (port $port).", last)
    }
}
