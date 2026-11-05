package dev.ontask

import dev.ontask.report.ItemRef
import dev.ontask.report.Run
import dev.ontask.report.applyRules
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneOverlayTest {
    private val ws = Category.WORK_SUPPORT
    private val ent = Category.ENTERTAINMENT
    private val run = Run(1000, 1900, ws.level, mapOf("Podcast Addict" to 600L, "Slack" to 300L), ws,
        mapOf("Podcast Addict" to ItemRef("Podcast Addict", pkg = "com.bambuna.podcastaddict"), "Slack" to ItemRef("Slack", pkg = "com.Slack")), phone = true)

    @Test fun aChangedPhoneAppGetsItsOwnPieceAndMovesTheTotals() {
        val totals = hashMapOf(Level.DEEP to 900L)
        val out = applyRules(run, { r -> if (r.pkg == "com.bambuna.podcastaddict") ent else null }, totals)
        assertEquals(listOf(ent, ws), out.map { it.category })
        assertEquals(listOf(600L, 300L), out.map { it.end - it.start })
        assertEquals(300L, totals[Level.DEEP]); assertEquals(600L, totals[Level.WASTED])
    }

    @Test fun nothingChangedLeavesTheStretchAlone() {
        val out = applyRules(run, { ws }, hashMapOf())
        assertEquals(listOf(run), out)
    }
}
