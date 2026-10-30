package dev.ontask.block

import dev.ontask.db.Db
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Local outbox of things the partner/cloud care about (work-hours change, work unblock, binge alert, check-in...).
 * Phase 4 sync drains rows with synced = 0.
 */
class Events(private val db: Db) {
    data class Row(val id: Long, val ts: Long, val type: String, val payload: JsonObject)

    fun emit(type: String, now: Long, vararg fields: Pair<String, Any?>) {
        val payload = buildJsonObject {
            fields.forEach { (k, v) ->
                when (v) {
                    null -> {}
                    is Number -> put(k, JsonPrimitive(v)); is Boolean -> put(k, JsonPrimitive(v)); else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }
        db.use { c ->
            c.prepareStatement("INSERT INTO events(ts,type,payload) VALUES (?,?,?)").use {
                it.setLong(1, now); it.setString(2, type); it.setString(3, payload.toString()); it.executeUpdate()
            }
        }
    }

    fun pending(limit: Int = 100): List<Row> = db.use { c ->
        c.prepareStatement("SELECT id,ts,type,payload FROM events WHERE synced=0 ORDER BY id LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Row(rs.getLong(1), rs.getLong(2), rs.getString(3), Json.parseToJsonElement(rs.getString(4)) as JsonObject)) }
            }
        }
    }

    fun markSynced(ids: List<Long>) = db.use { c ->
        c.prepareStatement("UPDATE events SET synced=1 WHERE id=?").use { ps -> ids.forEach { ps.setLong(1, it); ps.executeUpdate() } }
    }

    fun recent(type: String? = null, limit: Int = 50): List<Row> = db.use { c ->
        c.prepareStatement("SELECT id,ts,type,payload FROM events ${if (type != null) "WHERE type=?" else ""} ORDER BY id DESC LIMIT $limit").use { ps ->
            if (type != null) ps.setString(1, type)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Row(rs.getLong(1), rs.getLong(2), rs.getString(3), Json.parseToJsonElement(rs.getString(4)) as JsonObject)) } }
        }
    }
}
