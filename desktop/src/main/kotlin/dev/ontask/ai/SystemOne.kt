package dev.ontask.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One answer to a choice question: the most likely option and its probability (calibrated by the model). */
data class Decision(val choice: String, val probability: Double, val probabilities: Map<String, Double>, val ms: Long, val inTokens: Int?)

class DecisionException(msg: String, val status: Int? = null) : Exception(msg)

/**
 * Client for the System One decision API, which TypeSafe's Jev (cloud) and the open-source Kev (local) both speak:
 * POST {base}/v1/systemone with a text `state` and named questions; answers come back with probabilities.
 */
class SystemOneClient(private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {

    /** Asks which of [options] (name → description, or null) fits [state]. */
    fun choose(baseUrl: String, model: String, apiKey: String?, state: String, instructions: String, options: Map<String, String?>, timeoutSec: Long = 30): Decision {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            putJsonObject("questions") {
                putJsonObject("c") {
                    put("type", "choice")
                    put("instructions", instructions)
                    putJsonObject("criteria") { options.forEach { (k, v) -> if (v == null) put(k, JsonNull) else put(k, v) } }
                }
            }
        }
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/v1/systemone"))
            .timeout(Duration.ofSeconds(timeoutSec)).header("Content-Type", "application/json")
            .also { if (!apiKey.isNullOrBlank()) it.header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val t0 = System.nanoTime()
        val resp = try { http.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw DecisionException("unreachable: ${e.message}") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (resp.statusCode() !in 200..299) throw DecisionException("returned ${resp.statusCode()}: ${resp.body().take(200)}", resp.statusCode())
        val o = Json.parseToJsonElement(resp.body()).jsonObject
        val a = (o["answers"] as? JsonObject)?.get("c")?.jsonObject ?: throw DecisionException("unexpected response: ${resp.body().take(200)}")
        val probs = (a["probabilities"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }.orEmpty()
        val choice = a["choice"]?.jsonPrimitive?.contentOrNull ?: probs.maxByOrNull { it.value }?.key ?: throw DecisionException("no answer")
        val inTok = (o["usage"] as? JsonObject)?.get("input_tokens")?.jsonPrimitive?.intOrNull
        return Decision(choice, probs[choice] ?: 0.0, probs, ms, inTok)
    }
}
