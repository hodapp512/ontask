package dev.ontask.block

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Installs/removes the root hosts daemon by running daemon/install.sh. Nothing here runs unless the user asks:
 * the real install goes through the macOS administrator prompt (osascript).
 */
class RootHelper(private val daemonDir: File = locateDaemonDir(), private val prompt: Boolean = true) {

    data class Result(val ok: Boolean, val output: String)

    val available: Boolean get() = File(daemonDir, "install.sh").exists()
    val installed: Boolean get() = Files.exists(Path.of("/Library/LaunchDaemons/dev.ontask.daemon.plist"))

    fun install(staged: Path, port: Int, dryRun: Boolean = false, prefix: Path? = null, target: String = "/etc/hosts"): Result {
        val args = mutableListOf("--staged", staged.toString(), "--port", port.toString(), "--target", target)
        if (dryRun) args += "--dry-run"
        if (prefix != null) args += listOf("--prefix", prefix.toString())
        return run("install.sh", args, asAdmin = prompt && !dryRun && prefix == null)
    }

    fun uninstall(dryRun: Boolean = false, prefix: Path? = null): Result {
        val args = mutableListOf<String>()
        if (dryRun) args += "--dry-run"
        if (prefix != null) args += listOf("--prefix", prefix.toString())
        return run("uninstall.sh", args, asAdmin = prompt && !dryRun && prefix == null)
    }

    private fun run(script: String, args: List<String>, asAdmin: Boolean): Result {
        val path = File(daemonDir, script).absolutePath
        val cmd = if (asAdmin) {
            val shell = (listOf("/bin/bash", path) + args).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            listOf("osascript", "-e", "do shell script \"${shell.replace("\\", "\\\\").replace("\"", "\\\"")}\" with administrator privileges")
        } else listOf("/bin/bash", path) + args
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            Result(p.waitFor() == 0, out.trim())
        } catch (e: Exception) { Result(false, e.message ?: "failed") }
    }

    companion object {
        /** Packaged app: bundled under the app resources dir; dev: ../daemon. */
        fun locateDaemonDir(): File {
            System.getProperty("compose.application.resources.dir")?.let { File(it, "daemon").takeIf { d -> d.exists() }?.let { d -> return d } }
            return File("../daemon").absoluteFile
        }
    }
}

/**
 * Starting at login and reopening after a quit or crash, via one per-user LaunchAgent (no admin rights needed).
 * launchd restarts OnTask whenever it exits unsuccessfully: crashes do that, and so does Quit while "reopen" is on
 * ([QUIT_REOPEN]). A clean exit (code 0) lets it stay closed.
 */
class LoginItem(
    private val agentsDir: Path = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"),
    private val run: (List<String>) -> Int = { cmd -> runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() }.getOrDefault(-1) },
) {
    private val label = "dev.ontask.app"
    private val plist: Path get() = agentsDir.resolve("$label.plist")
    private val domain by lazy { "gui/" + (runCatching { ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim() }.getOrNull() ?: "501") }

    val enabled: Boolean get() = Files.exists(plist)

    /** Path of the packaged app executable, or null when running from Gradle. */
    fun appExecutable(): String? = System.getProperty("jpackage.app-path")

    /** True when this process was started by the agent (which sets ONTASK_AGENT). */
    val managed: Boolean get() = System.getenv("ONTASK_AGENT") == "1"

    /** Writes (or removes, when both are off) the agent. Takes effect for the next start; see [handOff]. */
    fun set(atLogin: Boolean, executable: String? = appExecutable(), keepAlive: Boolean = true): Boolean {
        if (!atLogin && !keepAlive) { Files.deleteIfExists(plist); return true }
        val exe = executable ?: return false
        Files.createDirectories(agentsDir)
        Files.writeString(
            plist,
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key><array><string>$exe</string></array>
  <key>EnvironmentVariables</key><dict><key>ONTASK_AGENT</key><string>1</string></dict>
  <key>RunAtLoad</key>${if (atLogin) "<true/>" else "<false/>"}
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>ProcessType</key><string>Interactive</string>
</dict></plist>
""",
        )
        return true
    }

    /** Asks launchd to run OnTask with the current agent (reloading it, in case an older definition is loaded). */
    fun handOff() {
        run(listOf("launchctl", "bootout", "$domain/$label"))
        run(listOf("launchctl", "bootstrap", domain, plist.toString()))
        run(listOf("launchctl", "kickstart", "$domain/$label"))
    }

    companion object {
        /** Exit code for Quit when OnTask should come back (anything but 0 makes launchd restart it). */
        const val QUIT_REOPEN = 75
    }
}
