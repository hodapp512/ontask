package dev.ontask.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A downloadable local model. One multimodal model serves both tiers so only one is ever loaded. */
data class CatalogModel(val label: String, val ollamaTag: String, val gguf: String, val approxGb: Double, val default: Boolean = false)

object ModelCatalog {
    // 4-bit builds. The GGUF repo ids are what `llama-server -hf` downloads; verify them if a download 404s.
    val all = listOf(
        CatalogModel("Qwen3-VL 4B (recommended)", "qwen3-vl:4b", "Qwen/Qwen3-VL-4B-Instruct-GGUF", 3.3, default = true),
        CatalogModel("Gemma 3 4B", "gemma3:4b", "ggml-org/gemma-3-4b-it-GGUF", 3.3),
        CatalogModel("Qwen2.5-VL 7B", "qwen2.5vl:7b", "ggml-org/Qwen2.5-VL-7B-Instruct-GGUF", 6.0),
    )
}

data class InstalledModel(val name: String, val sizeBytes: Long)

/** Talks to a local Ollama (detect, list, pull, delete). */
class OllamaAdmin(private val base: String = "http://127.0.0.1:11434", private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build()) {

    private fun get(path: String, timeoutMs: Long = 1500): HttpResponse<String>? = try {
        http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(timeoutMs)).GET().build(), HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) { null }

    fun detect(): Boolean = get("/api/version")?.statusCode() == 200

    fun list(): List<InstalledModel> {
        val r = get("/api/tags") ?: return emptyList()
        if (r.statusCode() != 200) return emptyList()
        return Json.parseToJsonElement(r.body()).jsonObject["models"]?.jsonArray?.map {
            val o = it.jsonObject; InstalledModel(o["name"]!!.jsonPrimitive.content, o["size"]?.jsonPrimitive?.longOrNull ?: 0)
        } ?: emptyList()
    }

    fun has(tag: String) = list().any { it.name == tag || it.name == "$tag:latest" }

    fun delete(name: String): Boolean = try {
        val body = buildJsonObject { put("name", name) }.toString()
        http.send(HttpRequest.newBuilder(URI.create("$base/api/delete")).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    } catch (e: Exception) { false }

    /** Streams progress (0..1) while pulling; blocks until done. Returns true on success. */
    fun pull(name: String, onProgress: (Double, String) -> Unit): Boolean = try {
        val body = buildJsonObject { put("name", name) }.toString()
        val resp = http.send(HttpRequest.newBuilder(URI.create("$base/api/pull")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofLines())
        var ok = false
        resp.body().use { lines -> lines.forEach { l ->
            val o = Json.parseToJsonElement(l) as JsonObject
            val status = o["status"]?.jsonPrimitive?.content ?: ""
            val total = o["total"]?.jsonPrimitive?.longOrNull; val done = o["completed"]?.jsonPrimitive?.longOrNull
            onProgress(if (total != null && total > 0 && done != null) done.toDouble() / total else 0.0, status)
            if (status == "success") ok = true
        } }
        ok
    } catch (e: Exception) { false }
}

/**
 * Bundled llama.cpp `llama-server`, started on demand and stopped after it has been idle (so the model leaves memory).
 * The binary is looked up next to the app resources, then on PATH.
 */
class LlamaServer(private val dataDir: File, private val port: Int = 8081) {
    private var process: Process? = null
    private var model: String? = null
    @Volatile private var lastUse = System.currentTimeMillis()

    val binary: File? get() {
        System.getProperty("compose.application.resources.dir")?.let { File(it, "llama/llama-server").takeIf { f -> f.exists() }?.let { f -> return f } }
        return System.getenv("PATH").split(':').map { File(it, "llama-server") }.firstOrNull { it.canExecute() }
    }
    val available get() = binary != null
    val running get() = process?.isAlive == true

    /** Ensures the server runs [ggufRepo]; first start downloads the model (can take minutes). */
    @Synchronized fun ensure(ggufRepo: String): Boolean {
        lastUse = System.currentTimeMillis()
        if (running && model == ggufRepo) return true
        stop()
        val bin = binary ?: return false
        process = ProcessBuilder(bin.path, "-hf", ggufRepo, "--host", "127.0.0.1", "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(File(dataDir, "llama-server.log")).start()
        model = ggufRepo
        repeat(600) { // up to ~10 min incl. download
            if (!process!!.isAlive) return false
            try { if (HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return true } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    fun touch() { lastUse = System.currentTimeMillis() }

    /** Call periodically; stops the server (unloading the model) after [idleMs] without use. */
    @Synchronized fun stopIfIdle(idleMs: Long = 120_000) { if (running && System.currentTimeMillis() - lastUse > idleMs) stop() }

    @Synchronized fun stop() { process?.destroy(); process = null; model = null }

    /** Models llama.cpp has downloaded (its own cache). */
    fun cached(): List<InstalledModel> =
        File(System.getProperty("user.home"), "Library/Caches/llama.cpp").listFiles { f -> f.name.endsWith(".gguf") }?.map { InstalledModel(it.name, it.length()) } ?: emptyList()

    fun deleteCached(name: String): Boolean = File(File(System.getProperty("user.home"), "Library/Caches/llama.cpp"), name).delete()
}
