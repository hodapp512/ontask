package dev.ontask

import dev.ontask.cloud.ThreeWay
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreeWayTest {
    private fun j(s: String) = JsonPrimitive(s)
    private val base = mapOf("a" to "\"1\"", "b" to "\"2\"", "c" to "\"3\"")

    @Test fun eachSideKeepsItsOwnChangesIncludingDeletes() {
        // Here: a changed, c deleted. There: b changed, d added.
        val r = ThreeWay.merge(base, mapOf("a" to j("9"), "b" to j("2")), mapOf("a" to j("1"), "b" to j("8"), "c" to j("3"), "d" to j("4")))
        assertEquals(mapOf("a" to "\"9\"", "b" to "\"8\"", "d" to "\"4\""), r.merged.mapValues { it.value.toString() })
        assertEquals(setOf("b", "d"), r.applyLocal.keys)        // taken in here
        assertTrue(r.remoteChanged)                              // a changed, c deleted there
    }

    @Test fun firstSyncUploadsWhatsHereAndTakesWhatsOnlyThere() {
        val r = ThreeWay.merge(emptyMap(), mapOf("a" to j("1")), mapOf("a" to j("5"), "z" to j("7")))
        assertEquals("\"1\"", r.merged["a"].toString())           // both have it: this side wins
        assertEquals(setOf("z"), r.applyLocal.keys)
    }

    @Test fun nothingChangedMeansNothingToDo() {
        val same = mapOf("a" to j("1"), "b" to j("2"), "c" to j("3"))
        val r = ThreeWay.merge(base, same, same)
        assertTrue(r.applyLocal.isEmpty()); assertFalse(r.remoteChanged)
    }

    @Test fun aDeleteThereIsAppliedHere() {
        val r = ThreeWay.merge(base, mapOf("a" to j("1"), "b" to j("2"), "c" to j("3")), mapOf("a" to j("1"), "b" to j("2")))
        assertEquals(mapOf<String, Any?>("c" to null), r.applyLocal.mapValues { it.value })
        assertFalse(r.remoteChanged)
    }
}
