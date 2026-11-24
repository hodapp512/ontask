package dev.ontask

import java.nio.file.Files
import java.nio.file.Path

/**
 * Stops a crash loop. When the interface stops, OnTask exits so launchd starts it again; if it keeps stopping
 * (3 times in 10 minutes), it would flash and quit forever (2026-11-22: 2,400 relaunches). Then it says so and
 * exits with 0 instead, which launchd doesn't restart. Opening OnTask by hand starts it again.
 */
class CrashGuard(private val file: Path, private val limit: Int = 3, private val windowSec: Long = 600) {
    /** Records a stop at [now] (epoch seconds); true when that makes too many and OnTask should stay closed. */
    fun record(now: Long): Boolean {
        val recent = runCatching { Files.readAllLines(file).mapNotNull { it.trim().toLongOrNull() } }.getOrDefault(emptyList())
            .filter { now - it < windowSec } + now
        runCatching { Files.write(file, recent.map { it.toString() }) }
        return recent.size >= limit
    }

    /** Called once the interface has stayed up a while: earlier stops no longer count. */
    fun clear() { runCatching { Files.deleteIfExists(file) } }

    companion object {
        /** A macOS alert (the interface itself is gone); waits for OK. "Show error log" reveals errors.log in Finder. */
        fun tellUser(log: Path) {
            val script = """
                set r to display alert "OnTask keeps stopping" message "It stopped 3 times in a few minutes, so it won't restart by itself. Open OnTask to try again. What went wrong is in errors.log." as critical buttons {"Show error log", "OK"} default button "OK"
                if button returned of r is "Show error log" then do shell script "open -R " & quoted form of "${log.toString().replace("\"", "\\\"")}"
            """.trimIndent()
            runCatching { ProcessBuilder("osascript", "-e", script).start().waitFor() }
        }
    }
}
