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

    @Test fun appliesStagedFileOnStartAndStripsOnlyOnTaskSections() {
        val dir = Files.createTempDirectory("ontask-daemon")
        val staged = dir.resolve("staged"); val target = dir.resolve("target")
        val ours = dev.ontask.block.HostsBuilder.build(listOf("reddit.com"), listOf("0.0.0.0 ads.example"))
        Files.writeString(staged, ours + "192.168.1.5 nas.local\n")
        val port = 13500 + (ProcessHandle.current().pid() % 400).toInt()
        val p = ProcessBuilder("bash", script.path, port.toString(), staged.toString(), target.toString())
            .also { it.environment()["ONTASK_DAEMON_SKIP_DNS"] = "1" }.redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile()).start()
        try {
            repeat(30) { if (Files.exists(target)) return@repeat; Thread.sleep(100) }
            assertTrue("0.0.0.0 reddit.com" in Files.readString(target)) // applied without being asked
        } finally { p.destroy() }
        val strip = ProcessBuilder("bash", script.path, "--strip", target.toString()).also { it.environment()["ONTASK_DAEMON_SKIP_DNS"] = "1" }.start()
        assertEquals(0, strip.waitFor())
        val left = Files.readString(target)
        assertTrue("reddit" !in left && "ads.example" !in left && "OnTask" !in left, left)
        assertTrue("127.0.0.1 localhost" in left && "192.168.1.5 nas.local" in left, left)
    }

    @Test fun cleansUpOnceTheAppHasBeenGoneLongEnough() {
        val dir = Files.createTempDirectory("ontask-daemon")
        val root = dir.resolve("root"); val app = Files.createDirectories(dir.resolve("OnTask.app"))
        val agent = dir.resolve("dev.ontask.app.plist").also { Files.writeString(it, "<plist/>") }
        val selfPlist = Files.createDirectories(root.resolve("Library/LaunchDaemons")).resolve("dev.ontask.daemon.plist").also { Files.writeString(it, "x") }
        val selfScript = Files.createDirectories(root.resolve("Library/PrivilegedHelperTools")).resolve("dev.ontask.daemon.sh").also { Files.writeString(it, "x") }
        val staged = dir.resolve("staged"); val target = dir.resolve("target")
        Files.writeString(staged, dev.ontask.block.HostsBuilder.build(listOf("reddit.com")))
        val port = 13900 + (ProcessHandle.current().pid() % 90).toInt()
        val p = ProcessBuilder("bash", script.path, port.toString(), staged.toString(), target.toString(), app.toString(), agent.toString())
            .also { it.environment().putAll(mapOf("ONTASK_DAEMON_SKIP_DNS" to "1", "ONTASK_DAEMON_CHECK_SEC" to "1", "ONTASK_DAEMON_GRACE_SEC" to "2", "ONTASK_DAEMON_PREFIX" to root.toString())) }
            .redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile()).start()
        try {
            Thread.sleep(2500)
            assertTrue(Files.exists(agent) && p.isAlive) // app still there: nothing happens
            Files.delete(app)
            repeat(80) { if (!p.isAlive) return@repeat; Thread.sleep(100) }
            val log = Files.readString(dir.resolve("log"))
            assertTrue(!p.isAlive, log)
            assertTrue("reddit" !in Files.readString(target), log)
            assertTrue(!Files.exists(agent) && !Files.exists(selfPlist) && !Files.exists(selfScript), log)
        } finally { p.destroy() }
    }
}

class RootHelperTest {
    @Test fun healthTellsMissingOutdatedBrokenAndOk() {
        val dir = Files.createTempDirectory("ontask-health")
        val root = dir.resolve("root")
        val staged = dir.resolve("hosts").also { Files.writeString(it, "127.0.0.1 localhost\n") }
        val target = dir.resolve("etc-hosts")
        val rh = dev.ontask.block.RootHelper(File("../daemon").absoluteFile, prompt = false, root = root)
        assertEquals(dev.ontask.block.HelperHealth.MISSING, rh.health(staged, target, "/Applications/OnTask.app"))
        assertTrue(rh.install(staged, 12999, prefix = root, appBundle = "/Applications/OnTask.app", agent = dir.resolve("agent.plist")).ok)
        assertEquals(dev.ontask.block.HelperHealth.OUTDATED, rh.health(staged, target, "/Other/OnTask.app")) // set up for another copy
        assertEquals(dev.ontask.block.HelperHealth.NOT_APPLYING, rh.health(staged, target, "/Applications/OnTask.app"))
        Files.copy(staged, target)
        assertEquals(dev.ontask.block.HelperHealth.OK, rh.health(staged, target, "/Applications/OnTask.app"))
        Files.writeString(root.resolve("Library/PrivilegedHelperTools/dev.ontask.daemon.sh"), "# old version")
        assertEquals(dev.ontask.block.HelperHealth.OUTDATED, rh.health(staged, target, "/Applications/OnTask.app"))
    }

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
