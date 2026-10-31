package dev.ontask.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class ProviderKind(val label: String, val local: Boolean, val defaultBaseUrl: String, val keyAccount: String?) {
    OLLAMA("Ollama (local)", true, "http://127.0.0.1:11434/v1", null),
    LLAMACPP("Built-in llama.cpp (local)", true, "http://127.0.0.1:8081/v1", null),
    CLAUDE("Claude (Anthropic)", false, "https://api.anthropic.com/v1", "anthropic"),
    OPENAI("OpenAI", false, "https://api.openai.com/v1", "openai"),
    GEMINI("Gemini (Google)", false, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini"),
}

/** One tier's choice: which provider and model. [baseUrl] overrides the default (tests, custom endpoints). */
data class ProviderConfig(val kind: ProviderKind, val model: String, val apiKey: String? = null, val baseUrl: String = kind.defaultBaseUrl)

data class LlmResult(val text: String, val inTokens: Int?, val outTokens: Int?, val ms: Long)

class LlmException(msg: String, val status: Int? = null) : Exception(msg)

/** OpenAI-compatible chat completions — one client for every provider. */
class LlmClient(private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {

    fun chat(cfg: ProviderConfig, system: String?, user: String, imagesB64: List<String> = emptyList(), maxTokens: Int = 200, timeoutSec: Long = 60): LlmResult {
        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", maxTokens)
            put("temperature", 0)
            putJsonArray("messages") {
                if (system != null) add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject {
                    put("role", "user")
                    if (imagesB64.isEmpty()) put("content", user)
                    else put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", user) })
                        imagesB64.forEach { img ->
                            add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:image/png;base64,$img") } })
                        }
                    })
                })
            }
        }
        val req = HttpRequest.newBuilder(URI.create(cfg.baseUrl.trimEnd('/') + "/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSec)).header("Content-Type", "application/json")
            .also { if (!cfg.apiKey.isNullOrBlank()) it.header("Authorization", "Bearer ${cfg.apiKey}") }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val t0 = System.nanoTime()
        val resp = try { http.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw LlmException("${cfg.kind.label} unreachable: ${e.message}") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (resp.statusCode() !in 200..299) throw LlmException("${cfg.kind.label} returned ${resp.statusCode()}: ${resp.body().take(200)}", resp.statusCode())
        val o = Json.parseToJsonElement(resp.body()).jsonObject
        val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw LlmException("Unexpected response from ${cfg.kind.label}")
        // "Thinking" models can spend the whole budget reasoning and return no answer at all.
        if (text.isBlank() && choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length")
            throw LlmException("${cfg.model} only thinks and never answers in time. Pick a non-thinking (instruct) model in Settings → AI labeling.")
        val usage = o["usage"] as? JsonObject
        return LlmResult(text, usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull, usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull, ms)
    }
}

/** Rough list prices (USD per million tokens, input/output) for the diagnostics estimate. Local models cost nothing. */
object Pricing {
    private val perMillion = mapOf(ProviderKind.CLAUDE to (1.0 to 5.0), ProviderKind.OPENAI to (0.15 to 0.60), ProviderKind.GEMINI to (0.10 to 0.40))
    fun estimate(kind: ProviderKind, inTok: Int?, outTok: Int?): Double {
        val (i, o) = perMillion[kind] ?: return 0.0
        return ((inTok ?: 0) * i + (outTok ?: 0) * o) / 1_000_000.0
    }
}
