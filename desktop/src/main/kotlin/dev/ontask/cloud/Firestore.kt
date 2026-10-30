package dev.ontask.cloud

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Converts plain JSON to/from the Firestore REST "typed value" format. (Nested arrays aren't allowed by Firestore.) */
object FsValue {
    fun encode(v: JsonElement): JsonObject = when (v) {
        is JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
        is JsonObject -> buildJsonObject { put("mapValue", buildJsonObject { put("fields", encodeFields(v)) }) }
        is JsonArray -> buildJsonObject { put("arrayValue", buildJsonObject { put("values", buildJsonArray { v.forEach { add(encode(it)) } }) }) }
        is JsonPrimitive -> when {
            v.isString -> buildJsonObject { put("stringValue", v.content) }
            v.booleanOrNull != null -> buildJsonObject { put("booleanValue", v.booleanOrNull!!) }
            v.longOrNull != null -> buildJsonObject { put("integerValue", v.content) }
            else -> buildJsonObject { put("doubleValue", v.doubleOrNull ?: 0.0) }
        }
    }

    fun encodeFields(o: JsonObject): JsonObject = buildJsonObject { o.forEach { (k, v) -> put(k, encode(v)) } }

    fun decode(v: JsonObject): JsonElement = when {
        "stringValue" in v -> v["stringValue"]!!
        "integerValue" in v -> JsonPrimitive((v["integerValue"] as JsonPrimitive).content.toLong())
        "doubleValue" in v -> v["doubleValue"]!!
        "booleanValue" in v -> v["booleanValue"]!!
        "nullValue" in v -> JsonNull
        "mapValue" in v -> decodeFields(v["mapValue"]!!.jsonObject["fields"] as? JsonObject ?: JsonObject(emptyMap()))
        "arrayValue" in v -> JsonArray((v["arrayValue"]!!.jsonObject["values"] as? JsonArray)?.map { decode(it.jsonObject) } ?: emptyList())
        "timestampValue" in v -> v["timestampValue"]!!
        else -> JsonNull
    }

    fun decodeFields(f: JsonObject): JsonObject = JsonObject(f.mapValues { decode(it.value.jsonObject) })
}
