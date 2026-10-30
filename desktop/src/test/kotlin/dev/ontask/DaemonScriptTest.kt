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
