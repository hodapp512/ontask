package dev.ontask

import dev.ontask.plan.Bedtime
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BedtimeTest {
    private val utc = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, utc).toEpochSecond()
    private val b = Bedtime(22 * 60 + 30, utc)
    private val night = LocalDate.of(2026, 10, 26)

    @Test fun theNightRunsFromBedtimeToFiveAm() {
        assertNull(b.nightOf(at(26, 22, 29)))
        assertEquals(night, b.nightOf(at(26, 22, 30)))
        assertEquals(night, b.nightOf(at(27, 1, 0)))  // after midnight still belongs to the 26th
        assertNull(b.nightOf(at(27, 5, 0)))
        assertEquals(LocalDate.of(2026, 10, 26), Bedtime(30, utc).nightOf(at(27, 0, 45))) // a 12:30 AM bedtime
        assertNull(Bedtime(30, utc).nightOf(at(26, 23, 0)))
    }

    @Test fun firstReminderAtBedtimeOnlyIfYoureThere() {
        assertNull(b.due(at(26, 22, 31), active = false, working = false, state = null))
        val n = b.due(at(26, 22, 31), active = true, working = true, state = null)!! // even when working, once
        assertEquals(1, n.level); assertEquals(0, n.lockSec)
    }

    @Test fun moreEveryFifteenMinutesWhileSurfingFirmerFromTheThird() {
        val s1 = Bedtime.NightState(night, count = 1, lastTs = at(26, 22, 31))
        assertNull(b.due(at(26, 22, 40), active = true, working = false, state = s1))
        val n2 = b.due(at(26, 22, 46), active = true, working = false, state = s1)!!
        assertEquals(2, n2.level); assertEquals(16, n2.minutesPast)
        assertNull(b.due(at(26, 22, 46), active = true, working = true, state = s1))        // working late: no nagging
        assertNull(b.due(at(26, 22, 46), active = false, working = false, state = s1))      // gone to bed
        val s2 = s1.copy(count = 2, lastTs = at(26, 22, 46))
        assertEquals(20, b.due(at(26, 23, 2), active = true, working = false, state = s2)!!.lockSec)
        assertNull(b.due(at(26, 23, 2), active = true, working = false, state = s2.copy(snoozeUntil = at(26, 23, 10))))
    }

    @Test fun aNewNightStartsOver() {
        val old = Bedtime.NightState(night, count = 5, lastTs = at(27, 1, 0))
        assertEquals(1, b.due(at(27, 22, 35), active = true, working = false, state = old)!!.level)
    }
}
