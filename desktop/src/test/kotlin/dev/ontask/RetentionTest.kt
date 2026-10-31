package dev.ontask

import dev.ontask.db.Db
import dev.ontask.db.Retention
import dev.ontask.db.Segment
import dev.ontask.db.TickStore
import dev.ontask.report.Reports
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RetentionTest {
    private val day = 86_400L
    private val now = Instant.parse("2026-10-30T12:00:00Z").epochSecond
    private val utc = ZoneOffset.UTC

    private fun seg(start: Long, n: Int, cat: Category, app: String = "Xcode", url: String? = null, domain: String? = null) =
        Segment(startTs = start, endTs = start + n * 5L, n = n, seconds = n * 5, app = app, bundle = null, title = null, url = url,
            domain = domain, category = cat, source = Source.RULE, ruleId = null, workTime = true, skippedUnchanged = n - 1, procMs = n * 2L)

    private fun count(db: Db, table: String) = db.use { c -> c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getInt(1) } } }
    private fun sum(db: Db, sql: String) = db.use { c -> c.createStatement().use { it.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }

    @Test fun rollsOldTicksIntoHourlyAndKeepsRecentOnes() {
        val db = Db.memory(); val store = TickStore(db)
        val old1 = now - 100 * day; val old2 = old1 + 60 // same hour, same bucket
        val recent = now - 10 * day
        store.save(listOf(seg(old1, 12, Category.CORE_WORK), seg(old2, 6, Category.CORE_WORK), seg(old1 + 600, 4, Category.ENTERTAINMENT, "Chrome", "https://x.com", "x.com"), seg(recent, 3, Category.CORE_WORK)))

        assertEquals(3, Retention.rollup(db, now, 90))

        assertEquals(1, count(db, "ticks"))
        assertEquals(2, count(db, "hourly")) // (core work, Xcode) and (social, x.com)
        assertEquals((12 + 6) * 5L, sum(db, "SELECT seconds FROM hourly WHERE category='CORE_WORK'"))
        assertEquals(18L, sum(db, "SELECT ticks FROM hourly WHERE category='CORE_WORK'"))
        assertEquals(1, sum(db, "SELECT COUNT(*) FROM hourly WHERE subject='x.com'").toInt())
        assertEquals(36L, sum(db, "SELECT proc_ms FROM hourly WHERE category='CORE_WORK'"))
    }

    @Test fun idempotentAndAccumulatesAcrossRuns() {
        val db = Db.memory(); val store = TickStore(db)
        val t = now - 95 * day
        store.save(listOf(seg(t, 10, Category.CORE_WORK)))
        assertEquals(1, Retention.rollup(db, now, 90))
        assertEquals(0, Retention.rollup(db, now, 90))
        // Late segment in the same hour merges into the existing hourly row.
        store.save(listOf(seg(t + 120, 10, Category.CORE_WORK)))
        assertEquals(1, Retention.rollup(db, now, 90))
        assertEquals(1, count(db, "hourly")); assertEquals(100L, sum(db, "SELECT seconds FROM hourly"))
    }

    @Test fun boundaryKeepsSegmentsEndingAfterCutoff() {
        val db = Db.memory(); val store = TickStore(db)
        val cutoff = now - 90 * day
        store.save(listOf(seg(cutoff - 100, 40, Category.CORE_WORK))) // ends after cutoff
        assertEquals(0, Retention.rollup(db, now, 90)); assertEquals(1, count(db, "ticks"))
    }

    @Test fun reportsStillSeeRolledUpTimeWithoutDoubleCounting() {
        val db = Db.memory(); val store = TickStore(db)
        val start = Instant.parse("2026-06-01T10:00:00Z").epochSecond
        store.save(listOf(seg(start, 12, Category.CORE_WORK), seg(start + 600, 6, Category.ENTERTAINMENT, "Chrome", "https://x.com", "x.com")))
        val date = java.time.LocalDate.of(2026, 6, 1)
        val before = Reports(db, utc).daily(date)
        Retention.rollup(db, now, 90)
        val after = Reports(db, utc).daily(date)
        assertEquals(before.totalsAll, after.totalsAll)
        assertEquals(60L, after.totalsAll[Level.DEEP]); assertEquals(30L, after.totalsAll[Level.WASTED])
        assertEquals(10, after.worstHour?.first); assertEquals(30L, after.worstHour?.second)
        assertEquals(0, after.timeline.size) // raw timeline is gone after rollup
    }
}
