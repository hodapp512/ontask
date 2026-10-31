package dev.ontask

import java.io.File
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the real daemon script against temp files (no root, no /etc/hosts). */
class DaemonScriptTest {
    private val script = File("../daemon/ontask-daemon.sh").absoluteFile

    private fun send(port: Int, msg: String) {
        repeat(20) { try { Socket("127.0.0.1", port).use { it.getOutputStream().apply { write("$msg\n".toByteArray()); flush() } }; return } catch (e: Exception) { Thread.sleep(150) } }
        error("daemon not reachable")
    }

    @Test fun appliesValidStagedFileAndRefusesInvalid() {
        val dir = Files.createTempDirectory("ontask-daemon")
        val staged = dir.resolve("staged"); val target = dir.resolve("target")
        Files.writeString(target, "original\n")
        val port = 13000 + (ProcessHandle.current().pid() % 500).toInt()
        val p = ProcessBuilder("bash", script.path, port.toString(), staged.toString(), target.toString())
            .also { it.environment()["ONTASK_DAEMON_SKIP_DNS"] = "1" }.redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile()).start()
        try {
            Files.writeString(staged, "127.0.0.1 localhost\n0.0.0.0 reddit.com\n")
            send(port, "copy"); Thread.sleep(600)
            assertEquals("127.0.0.1 localhost\n0.0.0.0 reddit.com\n", Files.readString(target))
            Files.writeString(staged, "not a hosts line\n")
            send(port, "copy"); Thread.sleep(600)
            assertTrue(Files.readString(target).contains("reddit.com")) // unchanged
            assertTrue(Files.readString(dir.resolve("log")).contains("refusing"))
        } finally { p.destroy() }
    }
}

class RootHelperTest {
    @Test fun installIntoPrefixAndUninstall() {
        val dir = Files.createTempDirectory("ontask-root")
        val rh = dev.ontask.block.RootHelper(File("../daemon").absoluteFile, prompt = false)
        val r = rh.install(dir.resolve("hosts"), 12999, prefix = dir.resolve("root"))
        assertTrue(r.ok, r.output)
        assertTrue(Files.exists(dir.resolve("root/Library/LaunchDaemons/dev.ontask.daemon.plist")))
        assertTrue(Files.readString(dir.resolve("root/Library/LaunchDaemons/dev.ontask.daemon.plist")).contains("12999"))
        assertTrue(rh.install(dir.resolve("hosts"), 12999, dryRun = true).output.contains("dry-run"))
        assertTrue(rh.uninstall(prefix = dir.resolve("root")).ok)
        assertTrue(!Files.exists(dir.resolve("root/Library/LaunchDaemons/dev.ontask.daemon.plist")))
    }

    @Test fun loginItemWritesAgentPlist() {
        val dir = Files.createTempDirectory("ontask-agents")
        val li = dev.ontask.block.LoginItem(dir)
        assertTrue(!li.set(true, null)) // not packaged -> refuses
        assertTrue(li.set(true, "/Applications/OnTask.app/Contents/MacOS/OnTask") && li.enabled)
        li.set(false, keepAlive = false); assertTrue(!li.enabled)
        val l2 = dev.ontask.block.LoginItem(dir); l2.set(true, "/x/OnTask"); val xml = Files.readString(dir.resolve("dev.ontask.app.plist")); assertTrue("<key>SuccessfulExit</key><false/>" in xml && "<key>RunAtLoad</key><true/>" in xml)
    }
}
