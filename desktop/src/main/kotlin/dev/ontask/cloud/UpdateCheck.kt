package dev.ontask.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class UpdateInfo(val version: String, val pageUrl: String, val dmgUrl: String?, val notes: String)

/** Checks GitHub Releases for a newer version. Never downloads or installs anything by itself. */
class UpdateCheck(private val apiBase: String = "https://api.github.com", private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()) {

    /** Returns the latest release if it is newer than [current]; null if up to date or unreachable. */
    fun check(repo: String, current: String): UpdateInfo? {
        if (!Regex("[\\w.-]+/[\\w.-]+").matches(repo)) return null
        val r = try {
            http.send(HttpRequest.newBuilder(URI.create("$apiBase/repos/$repo/releases/latest")).timeout(Duration.ofSeconds(10)).header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) { return null }
        if (r.statusCode() != 200) return null
        val o = Json.parseToJsonElement(r.body()).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
        if (!isNewer(tag, current)) return null
        val dmg = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".dmg") == true }?.get("browser_download_url")?.jsonPrimitive?.content
        return UpdateInfo(tag.removePrefix("v"), o["html_url"]?.jsonPrimitive?.content ?: "", dmg, o["body"]?.jsonPrimitive?.content ?: "")
    }

    companion object {
        fun parse(v: String): List<Int> = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun isNewer(candidate: String, current: String): Boolean {
            val a = parse(candidate); val b = parse(current)
            for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
            return false
        }
        fun currentVersion(): String = UpdateCheck::class.java.getResourceAsStream("/version.properties")?.bufferedReader()?.readText()?.substringAfter("version=")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "0.0.0"
    }
}
