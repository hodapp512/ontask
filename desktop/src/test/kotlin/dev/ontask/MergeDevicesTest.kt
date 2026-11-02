package dev.ontask

import dev.ontask.report.DailyReport
import dev.ontask.report.Run
import dev.ontask.report.withDevices
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeDevicesTest {
    @Test fun thePhonesDayJoinsTheMacs() {
        val mac = DailyReport(LocalDate.of(2026, 11, 2), 1000, mapOf(Level.DEEP to 3600L), mapOf(Level.DEEP to 3600L),
            listOf(Run(1000, 4600, Level.DEEP)), emptyList(), 1, null, emptyList())
        val phone = Json.parseToJsonElement("""{"totals":{"WASTED":600,"DEEP":60},"workTotals":{"WASTED":600},
            "timeline":[{"a":5000,"b":5600,"k":"W","c":"Entertainment / News / Social","t":[{"n":"Instagram","s":600}]}],
            "distractionEvents":1,"topSites":[{"name":"Instagram","categoryId":"ENTERTAINMENT","seconds":600}]}""").jsonObject
        val m = withDevices(mac, listOf(phone to null))
        assertEquals(3660L, m.totalsAll[Level.DEEP]); assertEquals(600L, m.totalsAll[Level.WASTED]); assertEquals(600L, m.totalsWork[Level.WASTED])
        assertEquals(listOf(false, true), m.timeline.map { it.phone })
        assertEquals(6000L, m.timeline[1].start)
        assertEquals(2, m.distractionEvents)
        assertTrue(m.topSubjects.single().phone)
    }
}
