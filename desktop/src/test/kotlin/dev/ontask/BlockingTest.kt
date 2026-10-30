package dev.ontask

import dev.ontask.block.*
import dev.ontask.classify.TimeRange
import dev.ontask.classify.WorkSchedule
import dev.ontask.classify.WorkWeek
import dev.ontask.db.Db
import dev.ontask.db.Prefs
import dev.ontask.db.Settings
import dev.ontask.db.SettingsStore
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockingTest {
    private val utc = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0, day: Int = 26) = java.time.ZonedDateTime.of(2026, 10, day, h, m, 0, 0, utc).toEpochSecond() // Mon Oct 26

    private class Env(val schedule: WorkSchedule) {
        val db = Db.memory(); val prefs = Prefs(db); val events = Events(db)
        val dir = Files.createTempDirectory("ontask-block")
        var settings = Settings(schedule = schedule)
        val unblocks = Unblocks(db, prefs, events, ZoneOffset.UTC); val focus = Focus(db, events); val list = Blocklist(db)
        val svc = BlockingService(dir, prefs, list, unblocks, focus, ZoneOffset.UTC) { settings }
    }
    private fun env() = Env(WorkSchedule(WorkWeek(mapOf(1 to listOf(TimeRange(9 * 60, 17 * 60))))))

    @Test fun escalatingDelayResetsAt4am() {
        val e = env(); val u = e.unblocks
        val t = at(10)
        assertEquals(10, u.nextDelaySec(t))
        u.startRegular(listOf("reddit.com"), 5, t, 10)
        assertEquals(30, u.nextDelaySec(t + 60))
        u.startRegular(listOf("reddit.com"), 5, t + 60, 30)
        assertEquals(120, u.nextDelaySec(t + 120))
        u.startRegular(listOf("reddit.com"), 5, t + 120, 120)
        assertEquals(300, u.nextDelaySec(t + 180))
        u.startRegular(listOf("reddit.com"), 5, t + 180, 300)
        assertEquals(300, u.nextDelaySec(t + 240)) // 4th+ stays at 5m
        assertEquals(4, u.todayCount(t + 240))
        // 3:59 next morning is still "today"; 4:00 resets
        assertEquals(300, u.nextDelaySec(at(3, 59, 27)))
        assertEquals(10, u.nextDelaySec(at(4, 0, 27)))
    }

    @Test fun delayIsEnforcedAndDurationsRestricted() {
        val u = env().unblocks
        assertFailsWith<UnblockRejected> { u.startRegular(listOf("x.com"), 5, at(10), 3) }
        assertFailsWith<UnblockRejected> { u.startRegular(listOf("x.com"), 7, at(10), 10) }
    }

    @Test fun purposeUnblockHasNoDelayNotifiesAndIsCapped() {
        val e = env()
        e.unblocks.startPurpose("youtube.com", "Acme onboarding videos", 60, at(10))
        assertEquals(0, e.unblocks.todayCount(at(10))) // not counted as a regular unblock
        assertEquals(listOf("work_unblock"), e.events.pending().map { it.type })
        assertFailsWith<UnblockRejected> { e.unblocks.startPurpose("youtube.com", "  ", 30, at(10)) }
        assertFailsWith<UnblockRejected> { e.unblocks.startPurpose("youtube.com", "x", 181, at(10)) }
    }

    @Test fun hostsOnlyBlockInWorkHoursAndUnblocksExpire() {
        val e = env(); e.list.add(listOf("reddit.com", "https://www.youtube.com/watch"), 0)
        assertEquals(listOf("reddit.com", "youtube.com"), e.list.all())
        assertEquals(emptyList(), e.svc.currentlyBlocked(at(20)))            // evening: anything goes
        assertEquals(listOf("reddit.com", "youtube.com"), e.svc.currentlyBlocked(at(10)))
        e.unblocks.startRegular(listOf("reddit.com"), 5, at(10), 10)
        assertEquals(listOf("youtube.com"), e.svc.currentlyBlocked(at(10, 2)))
        assertEquals(listOf("reddit.com", "youtube.com"), e.svc.currentlyBlocked(at(10, 6))) // expired after 5 min
    }

    @Test fun focusSessionAppliesStrictRulesOutsideWorkHours() {
        val e = env(); e.list.add(listOf("reddit.com"), 0)
        assertFalse(e.svc.strictNow(at(20)))
        e.focus.start(45, at(20))
        assertTrue(e.svc.strictNow(at(20, 30)))
        assertEquals(listOf("reddit.com"), e.svc.currentlyBlocked(at(20, 30)))
        e.focus.settle(at(21)); assertFalse(e.svc.strictNow(at(21)))
        assertTrue(e.focus.recent().first().completed)
    }

    @Test fun dryRunWritesStagedAndSimulatedTargetOnly() {
        val e = env(); e.list.add(listOf("reddit.com"), 0)
        assertEquals(BlockMode.DRY_RUN, e.svc.mode)
        assertTrue(e.svc.tick(at(10)))
        assertFalse(e.svc.tick(at(10, 1))) // unchanged -> no rewrite
        val staged = Files.readString(e.svc.stagedFile)
        assertTrue("0.0.0.0 reddit.com" in staged && "0.0.0.0 www.reddit.com" in staged && "127.0.0.1 localhost" in staged)
        assertEquals(staged, Files.readString(e.svc.dryRunTarget))
        assertTrue(e.svc.tick(at(18))) // work over -> block section emptied
        assertFalse("reddit" in Files.readString(e.svc.stagedFile))
    }

    @Test fun shorteningWorkHoursNeedsReasonAndNotifies_addingDoesNot() {
        val e = env()
        val store = SettingsStore(e.db)
        val editor = WorkHoursEditor(store, e.events, { e.settings }) { e.settings = it }
        val date = LocalDate.of(2026, 10, 28) // Wednesday: no template hours in this env
        // adding hours: no reason, no event
        editor.saveException(date, listOf(TimeRange(9 * 60, 12 * 60)), null, 1)
        assertTrue(e.events.pending().isEmpty())
        // shortening: reason required
        val ex = assertFailsWith<ReasonRequired> { editor.saveException(date, listOf(TimeRange(9 * 60, 10 * 60)), " ", 2) }
        assertEquals(120, ex.removedMinutes)
        editor.saveException(date, listOf(TimeRange(9 * 60, 10 * 60)), "Doctor appointment", 3)
        val ev = e.events.pending().single()
        assertEquals("work_hours_change", ev.type)
        assertTrue("Doctor appointment" in ev.payload.toString() && "2h" in ev.payload.toString())
        // exception persisted and effective
        assertTrue(store.load().schedule.isWork(java.time.ZonedDateTime.of(2026, 10, 28, 9, 30, 0, 0, utc).toEpochSecond(), utc))
        // template: removing Monday hours needs a reason
        assertFailsWith<ReasonRequired> { editor.saveTemplate(WorkWeek(), null, 4) }
    }

    @Test fun domainNormalization() {
        assertEquals("reddit.com", normalizeDomain(" https://www.Reddit.com/r/x "))
        assertEquals(null, normalizeDomain("# comment")); assertEquals(null, normalizeDomain("localhost")); assertEquals(null, normalizeDomain("a b.com"))
        assertEquals("ads.example.com", normalizeDomain("0.0.0.0 ads.example.com # tracker"))
    }

    @Test fun adBlockSanitize() {
        val raw = "# c\n127.0.0.1 localhost\n0.0.0.0 0.0.0.0\n0.0.0.0 ads.example.com\n127.0.0.1 t.example.net # x\n1.2.3.4 evil.example\n0.0.0.0 ads.example.com"
        assertEquals(listOf("0.0.0.0 ads.example.com", "0.0.0.0 t.example.net"), dev.ontask.block.AdBlock.sanitize(raw))
        val hosts = dev.ontask.block.HostsBuilder.build(listOf("reddit.com"), listOf("0.0.0.0 ads.example.com", "0.0.0.0 reddit.com"))
        assertTrue(hosts.contains("0.0.0.0 ads.example.com")); assertEquals(1, Regex("0.0.0.0 reddit.com\n").findAll(hosts).count())
    }
}
