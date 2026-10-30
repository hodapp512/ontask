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
    val blockMeDaemonPresent: Boolean get() = runCatching { File("/Library/LaunchDaemons").list()?.any { it.contains("blockme", ignoreCase = true) } == true }.getOrDefault(false)

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

/** "Start at login" via a per-user LaunchAgent (no admin rights needed). */
class LoginItem(private val agentsDir: Path = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents")) {
    private val label = "dev.ontask.app"
    private val plist: Path get() = agentsDir.resolve("$label.plist")

    val enabled: Boolean get() = Files.exists(plist)

    /** Path of the packaged app executable, or null when running from Gradle. */
    fun appExecutable(): String? = System.getProperty("jpackage.app-path")

    fun set(on: Boolean, executable: String? = appExecutable()): Boolean {
        if (!on) { Files.deleteIfExists(plist); return true }
        val exe = executable ?: return false
        Files.createDirectories(agentsDir)
        Files.writeString(
            plist,
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key><array><string>$exe</string></array>
  <key>RunAtLoad</key><true/>
</dict></plist>
""",
        )
        return true
    }
}
