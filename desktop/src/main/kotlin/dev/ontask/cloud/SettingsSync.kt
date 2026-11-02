package dev.ontask.cloud

import dev.ontask.db.Db
import dev.ontask.db.Prefs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Three-way merge of a keyed set: [base] is what was last synced (key → value as JSON text), [local] and [remote]
 * what each side has now. Each key takes whichever side changed it since [base] (this side when both did); a key
 * missing on a side that had it in [base] was deleted there.
 */
object ThreeWay {
    data class Result(val merged: Map<String, JsonElement>, val applyLocal: Map<String, JsonElement?>, val remoteChanged: Boolean)

    fun merge(base: Map<String, String>, local: Map<String, JsonElement>, remote: Map<String, JsonElement>): Result {
        val merged = HashMap<String, JsonElement>()
        val applyLocal = HashMap<String, JsonElement?>()
        for (k in base.keys + local.keys + remote.keys) {
            val b = base[k]; val l = local[k]; val r = remote[k]
            val localChanged = l?.toString() != b; val remoteChanged = r?.toString() != b
            val pick = if (localChanged || !remoteChanged) l else r
            if (pick != null) merged[k] = pick
            if (pick?.toString() != l?.toString()) applyLocal[k] = pick
        }
        val remoteDiffers = merged.keys != remote.keys || merged.any { (k, v) -> remote[k]?.toString() != v.toString() }
        return Result(merged, applyLocal, remoteDiffers)
    }
}

/** One set of settings kept in Firestore: how to read it here and apply a change from elsewhere. */
interface SyncedSet {
    val name: String
    fun local(): Map<String, JsonElement>
    /** Applies changes from another device ([value] null = deleted there). */
    fun apply(changes: Map<String, JsonElement?>)
}

/**
 * Settings, categories, rules and learned answers live in Firestore; this Mac keeps its local copy and syncs both
 * ways each pass. Sets are documents users/{uid}/sync/{name} holding `items` (key → value); verdicts (one per page
 * or video, many) are their own documents in users/{uid}/verdicts, exchanged incrementally.
 */
class SettingsSync(private val prefs: Prefs, private val sets: List<SyncedSet>, private val verdicts: VerdictSync?, private val deviceId: () -> String) {
    private val json = kotlinx.serialization.json.Json

    fun run(c: CloudClient, uid: String) {
        for (s in sets) runCatching { syncSet(c, uid, s) }
        verdicts?.let { v -> runCatching { v.sync(c, uid, deviceId()) } }
    }

    private fun syncSet(c: CloudClient, uid: String, s: SyncedSet) {
        val baseKey = "sync.base.${s.name}"
        val base: Map<String, String> = prefs.get(baseKey)?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content } }.getOrNull() } ?: emptyMap()
        val remoteDoc = c.get("users/$uid/sync/${s.name}")
        val remote = (remoteDoc?.get("items") as? JsonObject) ?: JsonObject(emptyMap())
        // First sync from this Mac with nothing remote: this Mac's settings are the starting point.
        val r = ThreeWay.merge(base, s.local(), remote)
        if (r.applyLocal.isNotEmpty()) s.apply(r.applyLocal)
        if (r.remoteChanged || remoteDoc == null) c.write("users/$uid/sync", s.name, buildJsonObject {
            put("items", JsonObject(r.merged)); put("updatedAt", System.currentTimeMillis()); put("updatedBy", deviceId())
        })
        prefs.set(baseKey, JsonObject(r.merged.mapValues { JsonPrimitive(it.value.toString()) }).toString())
    }
}

/**
 * Verdicts ("this video is Work") as documents users/{uid}/verdicts/{hash}: this Mac uploads the ones it decided
 * since its last upload and takes in those other devices decided since its last pull.
 */
class VerdictSync(private val db: Db, private val prefs: Prefs, private val applyRemote: (item: String, title: String?, category: String, source: String, sourceKey: String?, ts: Long) -> Unit) {
    fun sync(c: CloudClient, uid: String, device: String) {
        val upTo = prefs.long("sync.verdicts_up", 0)
        val mine = db.use { conn ->
            conn.prepareStatement("SELECT item, title, category, source, source_key, ts FROM verdicts WHERE ts > ? AND category IS NOT NULL ORDER BY ts LIMIT 200").use { ps ->
                ps.setLong(1, upTo)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6).toString())) } }
            }
        }
        for (v in mine) {
            c.write("users/$uid/verdicts", docId(v[0]!!), buildJsonObject {
                put("item", v[0]); v[1]?.let { put("title", it) }; put("category", v[2]); put("source", v[3]); v[4]?.let { put("sourceKey", it) }
                put("ts", v[5]!!.toLong()); put("by", device)
            })
        }
        if (mine.isNotEmpty()) prefs.set("sync.verdicts_up", mine.last()[5]!!)
        val since = prefs.long("sync.verdicts_down", 0)
        val theirs = c.queryAfter("users/$uid", "verdicts", "ts", since)
        for (o in theirs) {
            val ts = o["ts"]?.jsonPrimitive?.longOrNull ?: continue
            if (o["by"]?.jsonPrimitive?.contentOrNull != device) applyRemote(
                o["item"]!!.jsonPrimitive.content, o["title"]?.jsonPrimitive?.contentOrNull, o["category"]!!.jsonPrimitive.content,
                o["source"]?.jsonPrimitive?.contentOrNull ?: "USER_LABEL", o["sourceKey"]?.jsonPrimitive?.contentOrNull, ts,
            )
            prefs.set("sync.verdicts_down", maxOf(prefs.long("sync.verdicts_down", 0), ts).toString())
        }
    }

    private fun docId(item: String): String = java.security.MessageDigest.getInstance("SHA-1").digest(item.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Table-backed sets: rules, categories and learned source rules. */
object TableSets {
    fun rules(db: Db, changed: (List<Triple<String, String, String?>>) -> Unit) = object : SyncedSet {
        override val name = "rules"
        override fun local() = db.use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT kind, pattern, category FROM rules").use { rs ->
                buildMap { while (rs.next()) put("${rs.getString(1)}|${rs.getString(2)}", JsonPrimitive(rs.getString(3)) as JsonElement) }
            } }
        }
        override fun apply(changes: Map<String, JsonElement?>) {
            db.tx { c ->
                changes.forEach { (k, v) ->
                    val kind = k.substringBefore('|'); val pattern = k.substringAfter('|')
                    if (v == null) c.prepareStatement("DELETE FROM rules WHERE kind=? AND pattern=?").use { it.setString(1, kind); it.setString(2, pattern); it.executeUpdate() }
                    else c.prepareStatement("INSERT INTO rules(kind,pattern,category,created_ts) VALUES (?,?,?,?) ON CONFLICT(kind,pattern) DO UPDATE SET category=excluded.category")
                        .use { it.setString(1, kind); it.setString(2, pattern); it.setString(3, v.jsonPrimitive.content); it.setLong(4, System.currentTimeMillis() / 1000); it.executeUpdate() }
                }
            }
            changed(changes.map { (k, v) -> Triple(k.substringBefore('|'), k.substringAfter('|'), v?.jsonPrimitive?.content) })
        }
    }

    fun categories(db: Db, changed: () -> Unit) = object : SyncedSet {
        override val name = "categories"
        override fun local() = db.use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT name, label, level, variable, deleted, pos FROM categories").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), buildJsonObject {
                        put("label", rs.getString(2)); put("level", rs.getString(3)); put("variable", rs.getInt(4) == 1); put("deleted", rs.getInt(5) == 1); put("pos", rs.getInt(6))
                    } as JsonElement)
                }
            } }
        }
        override fun apply(changes: Map<String, JsonElement?>) {
            db.tx { c ->
                changes.forEach { (k, v) ->
                    if (v == null) c.prepareStatement("DELETE FROM categories WHERE name=?").use { it.setString(1, k); it.executeUpdate() }
                    else {
                        val o = v.jsonObject
                        c.prepareStatement("""INSERT INTO categories(name,label,level,variable,deleted,pos) VALUES (?,?,?,?,?,?)
                            ON CONFLICT(name) DO UPDATE SET label=excluded.label, level=excluded.level, variable=excluded.variable, deleted=excluded.deleted, pos=excluded.pos""").use {
                            it.setString(1, k); it.setString(2, o["label"]!!.jsonPrimitive.content); it.setString(3, o["level"]!!.jsonPrimitive.content)
                            it.setInt(4, if (o["variable"]?.jsonPrimitive?.content == "true") 1 else 0); it.setInt(5, if (o["deleted"]?.jsonPrimitive?.content == "true") 1 else 0)
                            it.setInt(6, o["pos"]?.jsonPrimitive?.intOrNull ?: 100); it.executeUpdate()
                        }
                    }
                }
            }
            changed()
        }
    }

    fun sourceRules(db: Db) = object : SyncedSet {
        override val name = "sourceRules"
        override fun local() = db.use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT source_key, category FROM source_rules").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), JsonPrimitive(rs.getString(2)) as JsonElement) }
            } }
        }
        override fun apply(changes: Map<String, JsonElement?>) = db.tx { c ->
            changes.forEach { (k, v) ->
                if (v == null) c.prepareStatement("DELETE FROM source_rules WHERE source_key=?").use { it.setString(1, k); it.executeUpdate() }
                else c.prepareStatement("INSERT INTO source_rules(source_key,category,ts) VALUES (?,?,?) ON CONFLICT(source_key) DO UPDATE SET category=excluded.category, ts=excluded.ts")
                    .use { it.setString(1, k); it.setString(2, v.jsonPrimitive.content); it.setLong(3, System.currentTimeMillis() / 1000); it.executeUpdate() }
            }
        }
    }
}
