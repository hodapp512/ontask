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

enum class BlockMode(val label: String, val explain: String) {
    OFF("Off", "OnTask never writes a hosts file."),
    DRY_RUN("Test mode", "OnTask writes its hosts file to its own folder only, so nothing is actually blocked. Useful to check what would be blocked."),
    LIVE("On", "OnTask's helper applies the block list to your Mac (/etc/hosts). Needs the helper installed."),
}

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
        get() = runCatching { BlockMode.valueOf(prefs.str("blocking.mode", "DRY_RUN")) }.getOrDefault(BlockMode.DRY_RUN)
        set(v) { prefs.set("blocking.mode", v.name); lastApplied = null; lastKey = null }
    val port: Int get() = prefs.int("blocking.port", 12901)

    /** Strict mode: work hours, or a running Focus session. */
    fun strictNow(now: Long): Boolean = settings().schedule.isWork(now, zone) || focus.active(now) != null

    fun currentlyBlocked(now: Long): List<String> {
        if (!strictNow(now)) return emptyList()
        val open = unblocks.active(now).map { it.domain }.toSet()
        return blocklist.all().filter { it !in open }
    }

    /** Idempotent; cheap enough to call every tick. Returns true if a new hosts file was written. */
    @Synchronized
    fun tick(now: Long): Boolean {
        focus.settle(now)
        if (mode == BlockMode.OFF) return false
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
            when (mode) {
                BlockMode.DRY_RUN -> Files.writeString(dryRunTarget, content)
                BlockMode.LIVE -> notifyDaemon()
                BlockMode.OFF -> {}
            }
            lastApplied = content; lastKey = key; lastError = null; true
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
        throw IOException("Could not reach the OnTask daemon on port $port — is the root helper installed?", last)
    }
}
