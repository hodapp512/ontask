package dev.ontask.cloud

import dev.ontask.ai.Keychain
import dev.ontask.db.Prefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CloudException(msg: String, val status: Int = 0) : Exception(msg)

/** Where the cloud lives: the bundled project (resources/cloud.properties), overridable in prefs for development. */
open class CloudConfig(val projectId: String, val apiKey: String, val region: String = "us-central1", val emulator: Boolean = false, val webUrl: String? = null) {
    override fun equals(other: Any?) = other is CloudConfig && other.projectId == projectId && other.apiKey == apiKey && other.region == region && other.emulator == emulator && other.webUrl == webUrl
    override fun hashCode() = listOf(projectId, apiKey, region, emulator, webUrl).hashCode()
    open val firestoreBase get() = if (emulator) "http://127.0.0.1:8080/v1/projects/$projectId/databases/(default)/documents" else "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
    open val authSignIn get() = (if (emulator) "http://127.0.0.1:9099/identitytoolkit.googleapis.com" else "https://identitytoolkit.googleapis.com") + "/v1/accounts:signInWithCustomToken?key=${enc(apiKey)}"
    open val authRefresh get() = (if (emulator) "http://127.0.0.1:9099/securetoken.googleapis.com" else "https://securetoken.googleapis.com") + "/v1/token?key=${enc(apiKey)}"
    open fun callable(name: String) = if (emulator) "http://127.0.0.1:5001/$projectId/$region/$name" else "https://$region-$projectId.cloudfunctions.net/$name"
    val webBase get() = webUrl ?: "https://$projectId.web.app"
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        /** Built-in project (resources/cloud.properties); prefs override it only for development (emulators, forks). */
        private val bundled: java.util.Properties by lazy {
            java.util.Properties().apply { CloudConfig::class.java.getResourceAsStream("/cloud.properties")?.use { load(it) } }
        }

        fun fromPrefs(p: Prefs): CloudConfig? {
            val emulator = p.bool("cloud.emulator", false)
            val project = p.get("cloud.project")?.takeIf { it.isNotBlank() } ?: (if (emulator) "demo-ontask" else bundled.getProperty("projectId")) ?: return null
            val apiKey = p.get("cloud.api_key")?.takeIf { it.isNotBlank() } ?: bundled.getProperty("apiKey") ?: "unused"
            return CloudConfig(project, apiKey, p.str("cloud.region", bundled.getProperty("region") ?: "us-central1"), emulator, p.get("cloud.web_url")?.takeIf { it.isNotBlank() } ?: bundled.getProperty("webUrl"))
        }
    }
}

/** Signs in with a custom token minted by the PWA, then speaks Firestore REST and HTTPS callables as that user. */
class CloudClient(private val cfg: CloudConfig, private val keychain: Keychain? = null, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var uid: String? = null; private set
    private var idToken: String? = null
    private var refreshToken: String? = keychain?.get("firebase-refresh")
    private var expiresAt = 0L

    val signedIn get() = uid != null || refreshToken != null

    private fun send(req: HttpRequest): HttpResponse<String> = try { http.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw CloudException("Network error: ${e.message}") }

    private fun post(url: String, body: String, auth: Boolean = false): JsonObject {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        if (auth) b.header("Authorization", "Bearer ${token()}")
        val r = send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build())
        if (r.statusCode() !in 200..299) throw CloudException("HTTP ${r.statusCode()}: ${r.body().take(300)}", r.statusCode())
        return json.parseToJsonElement(r.body().ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    }

    fun signInWithCustomToken(token: String) {
        val r = post(cfg.authSignIn, buildJsonObject { put("token", token.trim()); put("returnSecureToken", true) }.toString())
        adopt(r["idToken"]!!.jsonPrimitive.content, r["refreshToken"]!!.jsonPrimitive.content, r["expiresIn"]?.jsonPrimitive?.content?.toLong() ?: 3600)
        uid = decodeUid(idToken!!)
    }

    private fun adopt(id: String, refresh: String, expiresIn: Long) {
        idToken = id; refreshToken = refresh; expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        keychain?.set("firebase-refresh", refresh)
    }

    fun signOut() { uid = null; idToken = null; refreshToken = null; keychain?.delete("firebase-refresh") }

    private fun token(): String {
        val now = System.currentTimeMillis() / 1000
        if (idToken != null && now < expiresAt - 120) return idToken!!
        val rt = refreshToken ?: throw CloudException("Not linked to an account yet")
        val body = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(rt, Charsets.UTF_8)}"
        val r = send(HttpRequest.newBuilder(URI.create(cfg.authRefresh)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build())
        if (r.statusCode() !in 200..299) { signOut(); throw CloudException("Sign-in expired; link this Mac again", r.statusCode()) }
        val o = json.parseToJsonElement(r.body()).jsonObject
        adopt(o["id_token"]!!.jsonPrimitive.content, o["refresh_token"]!!.jsonPrimitive.content, o["expires_in"]!!.jsonPrimitive.content.toLong())
        uid = decodeUid(idToken!!)
        return idToken!!
    }

    fun ensureUid(): String { token(); return uid ?: throw CloudException("Not signed in") }

    private fun decodeUid(jwt: String): String {
        val payload = jwt.split(".")[1]
        val o = json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))))
        return o.jsonObject["user_id"]?.jsonPrimitive?.contentOrNull ?: o.jsonObject["sub"]!!.jsonPrimitive.content
    }

    // ---- Firestore ----------------------------------------------------------------------------------------

    /** Creates (auto id) or overwrites ([docId]) a document. */
    fun write(collectionPath: String, docId: String?, data: JsonObject) {
        val fields = buildJsonObject { put("fields", FsValue.encodeFields(data)) }.toString()
        val b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("Authorization", "Bearer ${token()}")
        val req = if (docId == null) b.uri(URI.create("${cfg.firestoreBase}/$collectionPath")).POST(HttpRequest.BodyPublishers.ofString(fields)).build()
        else b.uri(URI.create("${cfg.firestoreBase}/$collectionPath/$docId")).method("PATCH", HttpRequest.BodyPublishers.ofString(fields)).build()
        val r = send(req)
        if (r.statusCode() !in 200..299) throw CloudException("Firestore ${r.statusCode()}: ${r.body().take(300)}", r.statusCode())
    }

    fun delete(docPath: String) {
        val r = send(HttpRequest.newBuilder(URI.create("${cfg.firestoreBase}/$docPath")).header("Authorization", "Bearer ${token()}").DELETE().build())
        if (r.statusCode() !in 200..299 && r.statusCode() != 404) throw CloudException("Firestore ${r.statusCode()}", r.statusCode())
    }

    /** Reads one document; null if it doesn't exist. */
    fun get(docPath: String): JsonObject? {
        val r = send(HttpRequest.newBuilder(URI.create("${cfg.firestoreBase}/$docPath")).header("Authorization", "Bearer ${token()}").GET().build())
        if (r.statusCode() == 404) return null
        if (r.statusCode() !in 200..299) throw CloudException("Firestore ${r.statusCode()}: ${r.body().take(300)}", r.statusCode())
        return (json.parseToJsonElement(r.body()).jsonObject["fields"] as? JsonObject)?.let { FsValue.decodeFields(it) } ?: JsonObject(emptyMap())
    }

    /** Lists a (small) collection: document id to fields. */
    fun list(collectionPath: String): List<Pair<String, JsonObject>> {
        val r = send(HttpRequest.newBuilder(URI.create("${cfg.firestoreBase}/$collectionPath?pageSize=50")).header("Authorization", "Bearer ${token()}").GET().build())
        if (r.statusCode() !in 200..299) throw CloudException("Firestore ${r.statusCode()}: ${r.body().take(300)}", r.statusCode())
        val docs = json.parseToJsonElement(r.body()).jsonObject["documents"] as? JsonArray ?: return emptyList()
        return docs.map { d -> val o = d.jsonObject; o["name"]!!.jsonPrimitive.content.substringAfterLast('/') to ((o["fields"] as? JsonObject)?.let { FsValue.decodeFields(it) } ?: JsonObject(emptyMap())) }
    }

    /** Documents in [collectionId] (top level) where [field] == [equals]. */
    fun queryEq(collectionId: String, field: String, equals: String): List<JsonObject> {
        val q = """{"structuredQuery":{"from":[{"collectionId":"$collectionId"}],"where":{"fieldFilter":{"field":{"fieldPath":"$field"},"op":"EQUAL","value":{"stringValue":${JsonPrimitive(equals)}}}},"limit":20}}"""
        val url = cfg.firestoreBase.substringBefore("/documents") + "/documents:runQuery"
        val r = send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("Authorization", "Bearer ${token()}").POST(HttpRequest.BodyPublishers.ofString(q)).build())
        if (r.statusCode() !in 200..299) throw CloudException("Query ${r.statusCode()}: ${r.body().take(300)}", r.statusCode())
        return (json.parseToJsonElement(r.body()) as JsonArray).mapNotNull { (it.jsonObject["document"] as? JsonObject)?.get("fields") as? JsonObject }.map { FsValue.decodeFields(it) }
    }

    /** Collection-group query: documents named [group] where [field] == [equals] and ts > [after]. */
    fun queryGroup(group: String, field: String, equals: String, after: Long): List<JsonObject> {
        val q = """{"structuredQuery":{"from":[{"collectionId":"$group","allDescendants":true}],"where":{"compositeFilter":{"op":"AND","filters":[
            {"fieldFilter":{"field":{"fieldPath":"$field"},"op":"EQUAL","value":{"stringValue":${JsonPrimitive(equals)}}}},
            {"fieldFilter":{"field":{"fieldPath":"ts"},"op":"GREATER_THAN","value":{"integerValue":"$after"}}}]}},"orderBy":[{"field":{"fieldPath":"ts"},"direction":"ASCENDING"}],"limit":50}}"""
        val url = cfg.firestoreBase.substringBefore("/documents") + "/documents:runQuery"
        val r = send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("Authorization", "Bearer ${token()}").POST(HttpRequest.BodyPublishers.ofString(q)).build())
        if (r.statusCode() !in 200..299) throw CloudException("Query ${r.statusCode()}: ${r.body().take(300)}", r.statusCode())
        return (json.parseToJsonElement(r.body()) as JsonArray).mapNotNull { (it.jsonObject["document"] as? JsonObject)?.get("fields") as? JsonObject }.map { FsValue.decodeFields(it) }
    }

    /** Calls a callable that needs no sign-in (redeeming a link code). */
    fun callAnonymous(name: String, data: JsonObject): JsonObject =
        post(cfg.callable(name), buildJsonObject { put("data", data) }.toString())["result"] as? JsonObject ?: JsonObject(emptyMap())

    /** Calls an HTTPS callable function; returns its `result`. */
    fun call(name: String, data: JsonObject = JsonObject(emptyMap())): JsonObject =
        post(cfg.callable(name), buildJsonObject { put("data", data) }.toString(), auth = true)["result"] as? JsonObject ?: JsonObject(emptyMap())
}

