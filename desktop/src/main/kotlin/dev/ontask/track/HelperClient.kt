package dev.ontask.track

import dev.ontask.Sample
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedWriter
import java.io.File

interface SampleSource {
    suspend fun sample(): Sample?
}

/** Talks line-delimited JSON to the Swift helper; starts it lazily and restarts it if it dies or hangs. */
class HelperClient(private val binary: File) : SampleSource, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var lines = Channel<String>(Channel.UNLIMITED)
    private var seq = 0L
    private var consecutiveTimeouts = 0

    @Volatile var status: String = "not started"; private set
    @Volatile var restarts: Int = 0; private set
    @Volatile var failures: Int = 0; private set
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    private fun start(): Boolean {
        if (process?.isAlive == true) return true
        if (!binary.canExecute()) { status = "helper not found at $binary (run: swift build -c release in helper/)"; return false }
        return try {
            val p = ProcessBuilder(binary.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val ch = Channel<String>(Channel.UNLIMITED)
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { ch.trySend(it) } }
                ch.close()
            }.apply { isDaemon = true; name = "helper-reader" }.start()
            if (process != null) restarts++
            process = p; writer = p.outputStream.bufferedWriter(); lines = ch
            status = "running"; true
        } catch (e: Exception) {
            status = "failed to start: ${e.message}"; false
        }
    }

    private fun kill() {
        process?.destroyForcibly(); process = null; writer = null
    }

    /** Sends one command; returns the `result` object or null on failure. */
    suspend fun request(cmd: String, timeoutMs: Long = 8000): JsonObject? = mutex.withLock {
        if (!start()) return null
        val id = ++seq
        try {
            writer!!.apply { write("""{"id":$id,"cmd":"$cmd"}"""); newLine(); flush() }
            val resp = withTimeout(timeoutMs) {
                var found: JsonObject? = null
                while (found == null) {
                    val o = json.parseToJsonElement(lines.receive()).jsonObject
                    if (o["id"]?.jsonPrimitive?.longOrNull == id) found = o
                }
                found
            }
            consecutiveTimeouts = 0
            if (resp["ok"]?.jsonPrimitive?.boolean == true) resp["result"]?.jsonObject
            else { status = "error: ${resp["error"]}"; null }
        } catch (e: TimeoutCancellationException) {
            // A first-time Automation prompt can block the helper for a while; only restart if it keeps happening.
            status = "timeout"; if (++consecutiveTimeouts >= 3) { kill(); consecutiveTimeouts = 0 }
            null
        } catch (e: ClosedReceiveChannelException) {
            status = "helper exited"; kill(); null
        } catch (e: java.io.IOException) {
            status = "io error: ${e.message}"; kill(); null
        }
    }

    override suspend fun sample(): Sample? {
        val r = request("sample")
        if (r == null) { failures++; return null }
        return runCatching { json.decodeFromJsonElement(Sample.serializer(), r) }.getOrNull()
    }

    override fun close() = kill()
}
