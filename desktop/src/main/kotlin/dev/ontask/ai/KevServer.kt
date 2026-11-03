package dev.ontask.ai

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Kev sizes OnTask offers. [peakGb] is the memory needed while the model loads. */
enum class KevSize(val label: String, val repo: String, val peakGb: Double) {
    SMALL("Kev 0.8B (recommended, about 3 GB of memory)", "jaredpalmer/kev-0.8b", 4.7),
    LARGE("Kev 4B (fewer mistakes, about 10 GB of memory)", "jaredpalmer/kev-4b", 15.6),
}

/**
 * Kev (github.com/jaredpalmer/kev), run on this Mac: installed into OnTask's data folder (its code, a Python
 * environment made by `uv`, and the model weights), started when there is something to decide, and stopped after it
 * has been idle so its memory is freed. Install needs `uv` (`brew install uv`) and git.
 */
class KevServer(dataDir: File, private val port: Int = 8011) {
    private val home = File(dataDir, "models")
    private val repo = File(home, "kev")
    private val hfHome = File(home, "hf")
    private val log = File(home, "kev-server.log")
    private var process: Process? = null
    private var size: KevSize? = null
    @Volatile private var lastUse = System.currentTimeMillis()

    val baseUrl get() = "http://127.0.0.1:$port"
    val uv: File? get() = listOf("/opt/homebrew/bin/uv", "/usr/local/bin/uv", System.getProperty("user.home") + "/.local/bin/uv").map(::File).firstOrNull { it.canExecute() }
    val installed get() = File(repo, ".venv").isDirectory
    val running get() = process?.isAlive == true

    /** Whether the weights for [s] are already downloaded (otherwise the first start downloads them). */
    fun downloaded(s: KevSize) = File(hfHome, "hub/models--" + s.repo.replace("/", "--")).isDirectory

    /** Clones Kev and builds its Python environment. Blocks for a minute or two; [onLine] gets progress lines. */
    @Synchronized fun install(onLine: (String) -> Unit = {}): Boolean {
        val uv = uv ?: run { onLine("uv isn't installed. Run: brew install uv"); return false }
        home.mkdirs()
        if (!File(repo, ".git").isDirectory && !run(listOf("git", "clone", "--depth", "1", "https://github.com/jaredpalmer/kev.git", repo.path), home, onLine)) return false
        return run(listOf(uv.path, "sync", "--extra", "serve"), repo, onLine)
    }

    private fun run(cmd: List<String>, dir: File, onLine: (String) -> Unit): Boolean = try {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine(onLine)
        p.waitFor() == 0
    } catch (e: Exception) { onLine(e.message ?: "failed"); false }

    /** Starts the server with [s] if it isn't already running it; first start downloads the weights (minutes). */
    @Synchronized fun ensure(s: KevSize, timeoutSec: Int = 900): Boolean {
        lastUse = System.currentTimeMillis()
        if (running && size == s) return true
        stop()
        val uv = uv ?: return false
        if (!installed) return false
        process = ProcessBuilder(uv.path, "run", "--extra", "serve", "python", "-m", "kev.serve", "--run", s.repo, "--port", port.toString())
            .directory(repo).redirectErrorStream(true).redirectOutput(log)
            .also { it.environment()["HF_HOME"] = hfHome.path }
            .start()
        size = s
        val http = HttpClient.newHttpClient()
        repeat(timeoutSec) {
            if (process?.isAlive != true) return false
            try {
                if (http.send(HttpRequest.newBuilder(URI.create("$baseUrl/v1/models")).timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return true
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    fun touch() { lastUse = System.currentTimeMillis() }

    /** Call periodically; stops the server (freeing its memory) after [idleMs] without use. */
    @Synchronized fun stopIfIdle(idleMs: Long = 5 * 60_000L) { if (running && System.currentTimeMillis() - lastUse > idleMs) stop() }

    @Synchronized fun stop() { process?.let { it.descendants().forEach { d -> d.destroy() }; it.destroy() }; process = null; size = null }

    /** Deletes the downloaded weights of [s] (e.g. after switching size). */
    fun deleteWeights(s: KevSize): Boolean = File(hfHome, "hub/models--" + s.repo.replace("/", "--")).deleteRecursively()

    /** The last lines of the server log, for Diagnostics. */
    fun logTail(lines: Int = 5): String = runCatching { log.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
}
